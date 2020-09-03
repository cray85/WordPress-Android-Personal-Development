package org.wordpress.android.ui.reader.discover

import android.content.ActivityNotFoundException
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.analytics.AnalyticsTracker.Stat.APP_REVIEWS_EVENT_INCREMENTED_BY_OPENING_READER_POST
import org.wordpress.android.analytics.AnalyticsTracker.Stat.FOLLOWED_BLOG_NOTIFICATIONS_READER_ENABLED
import org.wordpress.android.analytics.AnalyticsTracker.Stat.READER_ARTICLE_VISITED
import org.wordpress.android.analytics.AnalyticsTracker.Stat.READER_SAVED_LIST_SHOWN
import org.wordpress.android.analytics.AnalyticsTracker.Stat.READER_SAVED_POST_OPENED_FROM_OTHER_POST_LIST
import org.wordpress.android.analytics.AnalyticsTracker.Stat.SHARED_ITEM_READER
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.store.AccountStore.AddOrDeleteSubscriptionPayload.SubscriptionAction.DELETE
import org.wordpress.android.fluxc.store.AccountStore.AddOrDeleteSubscriptionPayload.SubscriptionAction.NEW
import org.wordpress.android.models.ReaderPost
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.modules.DEFAULT_SCOPE
import org.wordpress.android.modules.UI_SCOPE
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.prefs.AppPrefsWrapper
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.OpenPost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.SharePost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBlogPreview
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedSavedOnlyLocallyDialog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedTab
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostDetail
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowReaderComments
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowVideoViewer
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.BLOCK_SITE
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.BOOKMARK
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.COMMENTS
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.FOLLOW
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.LIKE
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.REBLOG
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.SHARE
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.SITE_NOTIFICATIONS
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.VISIT_SITE
import org.wordpress.android.ui.reader.reblog.ReblogUseCase
import org.wordpress.android.ui.reader.repository.ReaderRepositoryCommunication
import org.wordpress.android.ui.reader.repository.ReaderRepositoryCommunication.Error.NetworkUnavailable
import org.wordpress.android.ui.reader.repository.ReaderRepositoryCommunication.Error.RemoteRequestFailure
import org.wordpress.android.ui.reader.repository.ReaderRepositoryCommunication.Started
import org.wordpress.android.ui.reader.repository.ReaderRepositoryCommunication.SuccessWithData
import org.wordpress.android.ui.reader.repository.usecases.BlockBlogUseCase
import org.wordpress.android.ui.reader.repository.usecases.BlockSiteState
import org.wordpress.android.ui.reader.repository.usecases.PostLikeUseCase
import org.wordpress.android.ui.reader.repository.usecases.UndoBlockBlogUseCase
import org.wordpress.android.ui.reader.usecases.BookmarkPostState.PreLoadPostContent
import org.wordpress.android.ui.reader.usecases.BookmarkPostState.Success
import org.wordpress.android.ui.reader.usecases.ReaderPostBookmarkUseCase
import org.wordpress.android.ui.reader.usecases.ReaderSiteFollowUseCase
import org.wordpress.android.ui.reader.usecases.ReaderSiteFollowUseCase.FollowSiteState
import org.wordpress.android.ui.reader.usecases.ReaderSiteFollowUseCase.FollowSiteState.PostFollowStatusChanged
import org.wordpress.android.ui.reader.usecases.ReaderSiteNotificationsUseCase
import org.wordpress.android.ui.reader.usecases.ReaderSiteNotificationsUseCase.SiteNotificationState
import org.wordpress.android.ui.utils.HtmlMessageUtils
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.util.analytics.AnalyticsTrackerWrapper
import org.wordpress.android.viewmodel.Event
import org.wordpress.android.viewmodel.ResourceProvider
import org.wordpress.android.widgets.AppRatingDialog.incrementInteractions
import javax.inject.Inject
import javax.inject.Named

class ReaderPostCardActionsHandler @Inject constructor(
    private val analyticsTrackerWrapper: AnalyticsTrackerWrapper,
    private val reblogUseCase: ReblogUseCase,
    private val bookmarkUseCase: ReaderPostBookmarkUseCase,
    private val followUseCase: ReaderSiteFollowUseCase,
    private val blockBlogUseCase: BlockBlogUseCase,
    private val likeUseCase: PostLikeUseCase,
    private val siteNotificationsUseCase: ReaderSiteNotificationsUseCase,
    private val undoBlockBlogUseCase: UndoBlockBlogUseCase,
    private val appPrefsWrapper: AppPrefsWrapper,
    private val dispatcher: Dispatcher,
    private val resourceProvider: ResourceProvider,
    private val htmlMessageUtils: HtmlMessageUtils,
    @Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher,
    @Named(UI_SCOPE) private val uiScope: CoroutineScope,
    @Named(DEFAULT_SCOPE) private val defaultScope: CoroutineScope
) {
    private val _navigationEvents = MediatorLiveData<Event<ReaderNavigationEvents>>()
    val navigationEvents: LiveData<Event<ReaderNavigationEvents>> = _navigationEvents

    private val _snackbarEvents = MediatorLiveData<Event<SnackbarMessageHolder>>()
    val snackbarEvents: LiveData<Event<SnackbarMessageHolder>> = _snackbarEvents

    private val _preloadPostEvents = MediatorLiveData<Event<PreLoadPostContent>>()
    val preloadPostEvents: LiveData<Event<PreLoadPostContent>> = _preloadPostEvents

    private val _followStatusUpdated = MediatorLiveData<PostFollowStatusChanged>()
    val followStatusUpdated: LiveData<PostFollowStatusChanged> = _followStatusUpdated

    // Used only in legacy ReaderPostListFragment. The discover tab observes reactive ReaderDiscoverDataProvider.
    private val _refreshPosts = MediatorLiveData<Event<Unit>>()
    val refreshPosts: LiveData<Event<Unit>> = _refreshPosts

    init {
        dispatcher.register(siteNotificationsUseCase)
    }

    suspend fun onAction(post: ReaderPost, type: ReaderPostCardActionType, isBookmarkList: Boolean) {
        withContext(bgDispatcher) {
            when (type) {
                FOLLOW -> handleFollowClicked(post)
                SITE_NOTIFICATIONS -> handleSiteNotificationsClicked(post.blogId)
                SHARE -> handleShareClicked(post)
                VISIT_SITE -> handleVisitSiteClicked(post)
                BLOCK_SITE -> handleBlockSiteClicked(post.blogId)
                LIKE -> handleLikeClicked(post)
                BOOKMARK -> handleBookmarkClicked(post.postId, post.blogId, isBookmarkList)
                REBLOG -> handleReblogClicked(post)
                COMMENTS -> handleCommentsClicked(post.postId, post.blogId)
            }
        }
    }

    suspend fun handleOnItemClicked(post: ReaderPost) {
        withContext(bgDispatcher) {
            incrementInteractions(APP_REVIEWS_EVENT_INCREMENTED_BY_OPENING_READER_POST)

            if (post.isBookmarked) {
                analyticsTrackerWrapper.track(READER_SAVED_POST_OPENED_FROM_OTHER_POST_LIST)
            }
            _navigationEvents.postValue(Event(ShowPostDetail(post)))
        }
    }

    suspend fun handleVideoOverlayClicked(videoUrl: String) {
        withContext(bgDispatcher) {
            _navigationEvents.postValue(Event(ShowVideoViewer(videoUrl)))
        }
    }

    suspend fun handleHeaderClicked(siteId: Long, feedId: Long) {
        withContext(bgDispatcher) {
            _navigationEvents.postValue(Event(ShowBlogPreview(siteId, feedId)))
        }
    }

    private suspend fun handleFollowClicked(post: ReaderPost) {
        followUseCase.toggleFollow(post).collect {
            when (it) {
                is FollowSiteState.Failed.NoNetwork -> {
                    _snackbarEvents.postValue(
                            Event(SnackbarMessageHolder((UiStringRes(R.string.error_network_connection))))
                    )
                }
                is FollowSiteState.Failed.RequestFailed -> {
                    _snackbarEvents.postValue(
                            Event(SnackbarMessageHolder((UiStringRes(R.string.reader_error_request_failed_title))))
                    )
                }
                is FollowSiteState.Success -> { // Do nothing
                }
                is PostFollowStatusChanged -> {
                    _followStatusUpdated.postValue(it)
                    siteNotificationsUseCase.fetchSubscriptions()

                    if (it.showEnableNotification) {
                        val action = prepareEnableNotificationSnackbarAction(post.blogName, post.blogId)
                        action.invoke()
                    } else if (it.deleteNotificationSubscription) {
                        siteNotificationsUseCase.updateSubscription(it.blogId, DELETE)
                        siteNotificationsUseCase.updateNotificationEnabledForBlogInDb(it.blogId, false)
                    }
                }
            }
        }
    }

    private suspend fun handleSiteNotificationsClicked(blogId: Long) {
        when (siteNotificationsUseCase.toggleNotification(blogId)) {
            is SiteNotificationState.Success, SiteNotificationState.Failed.AlreadyRunning -> { // Do Nothing
            }
            is SiteNotificationState.Failed.NoNetwork -> {
                _snackbarEvents.postValue(
                        Event(SnackbarMessageHolder((UiStringRes(R.string.error_network_connection))))
                )
            }
            is SiteNotificationState.Failed.RequestFailed -> {
                _snackbarEvents.postValue(
                        Event(SnackbarMessageHolder((UiStringRes(R.string.reader_error_request_failed_title))))
                )
            }
        }
    }

    private fun handleShareClicked(post: ReaderPost) {
        analyticsTrackerWrapper.track(SHARED_ITEM_READER, post.blogId)
        try {
            _navigationEvents.postValue(Event(SharePost(post)))
        } catch (ex: ActivityNotFoundException) {
            _snackbarEvents.postValue(Event(SnackbarMessageHolder(UiStringRes(R.string.reader_toast_err_share_intent))))
        }
    }

    private fun handleVisitSiteClicked(post: ReaderPost) {
        analyticsTrackerWrapper.track(READER_ARTICLE_VISITED)
        _navigationEvents.postValue(Event(OpenPost(post)))
    }

    private suspend fun handleBlockSiteClicked(blogId: Long) {
        // todo: Annmarie add tracking dependent upon implementation
        blockBlogUseCase.blockBlog(blogId).collect {
            when (it) {
                is BlockSiteState.SiteBlockedInLocalDb -> {
                    _refreshPosts.postValue(Event(Unit))
                    _snackbarEvents.postValue(
                            Event(
                                    SnackbarMessageHolder(
                                            UiStringRes(R.string.reader_toast_blog_blocked),
                                            UiStringRes(R.string.undo),
                                            {
                                                uiScope.launch {
                                                    undoBlockBlogUseCase.undoBlockBlog(it.blockedBlogData)
                                                    _refreshPosts.postValue(Event(Unit))
                                                }
                                            })
                            )
                    )
                }
                BlockSiteState.Success, BlockSiteState.Failed.AlreadyRunning -> {
                } // do nothing
                BlockSiteState.Failed.NoNetwork -> {
                    _snackbarEvents.postValue(
                            Event(SnackbarMessageHolder(UiStringRes(R.string.reader_toast_err_block_blog)))
                    )
                }
                BlockSiteState.Failed.RequestFailed -> {
                    _refreshPosts.postValue(Event(Unit))
                    _snackbarEvents.postValue(
                            Event(SnackbarMessageHolder(UiStringRes(R.string.reader_toast_err_block_blog)))
                    )
                }
            }
        }
    }

    private suspend fun handleLikeClicked(post: ReaderPost) {
        when (likeUseCase.perform(post, !post.isLikedByCurrentUser)) {
            is Started, is ReaderRepositoryCommunication.Success, is SuccessWithData<*> -> { }
            is NetworkUnavailable -> {
                _snackbarEvents.postValue(Event(SnackbarMessageHolder(UiStringRes(R.string.no_network_message))))
            }
            is RemoteRequestFailure -> {
                _snackbarEvents.postValue(
                        Event(SnackbarMessageHolder(UiStringRes(R.string.reader_error_request_failed_title)))
                )
            }
        }
    }

    private suspend fun handleBookmarkClicked(postId: Long, blogId: Long, isBookmarkList: Boolean) {
        bookmarkUseCase.toggleBookmark(blogId, postId, isBookmarkList).collect {
            when (it) {
                is PreLoadPostContent -> _preloadPostEvents.postValue(Event(PreLoadPostContent(blogId, postId)))
                is Success -> {
                    // Content needs to be manually refreshed in the legacy ReaderPostListAdapter
                    _refreshPosts.postValue(Event(Unit))

                    val showSnackbarAction = {
                        _snackbarEvents.postValue(
                                Event(
                                        SnackbarMessageHolder(
                                                UiStringRes(R.string.reader_bookmark_snack_title),
                                                UiStringRes(R.string.reader_bookmark_snack_btn),
                                                buttonAction = {
                                                    analyticsTrackerWrapper.track(
                                                            READER_SAVED_LIST_SHOWN,
                                                            mapOf("source" to "post_list_saved_post_notice")
                                                    )
                                                    _navigationEvents.postValue(Event(ShowBookmarkedTab))
                                                })
                                )
                        )
                    }
                    if (it.bookmarked && !isBookmarkList) {
                        if (appPrefsWrapper.shouldShowBookmarksSavedLocallyDialog()) {
                            _navigationEvents.postValue(
                                    Event(
                                            ShowBookmarkedSavedOnlyLocallyDialog(
                                                    okButtonAction = {
                                                        appPrefsWrapper.setBookmarksSavedLocallyDialogShown()
                                                        showSnackbarAction.invoke()
                                                    })
                                    )
                            )
                        } else {
                            showSnackbarAction.invoke()
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleReblogClicked(post: ReaderPost) {
        val state = reblogUseCase.onReblogButtonClicked(post)
        val navigationTarget = reblogUseCase.convertReblogStateToNavigationEvent(state)
        if (navigationTarget != null) {
            _navigationEvents.postValue(Event(navigationTarget))
        } else {
            _snackbarEvents.postValue(Event(SnackbarMessageHolder(UiStringRes(R.string.reader_reblog_error))))
        }
    }

    private fun handleCommentsClicked(postId: Long, blogId: Long) {
        _navigationEvents.postValue(Event(ShowReaderComments(blogId, postId)))
    }

    private fun prepareEnableNotificationSnackbarAction(blogName: String?, blogId: Long): () -> Unit {
        return {
            val thisSite = resourceProvider.getString(R.string.reader_followed_blog_notifications_this)
            val blog = if (blogName?.isEmpty() == true) thisSite else blogName
            val notificationMessage = htmlMessageUtils
                    .getHtmlMessageFromStringFormatResId(
                            R.string.reader_followed_blog_notifications,
                            "<b>",
                            blog,
                            "</b>"
                    )
            _snackbarEvents.postValue(
                    Event(
                            SnackbarMessageHolder(
                                    UiStringText(notificationMessage),
                                    UiStringRes(R.string.reader_followed_blog_notifications_action),
                                    buttonAction = {
                                        defaultScope.launch {
                                            analyticsTrackerWrapper
                                                    .track(FOLLOWED_BLOG_NOTIFICATIONS_READER_ENABLED, blogId)
                                            siteNotificationsUseCase.updateSubscription(blogId, NEW)
                                            siteNotificationsUseCase.updateNotificationEnabledForBlogInDb(blogId, true)
                                        }
                                    }
                            )
                    )
            )
        }
    }

    fun onCleared() {
        dispatcher.unregister(siteNotificationsUseCase)
    }
}
