package org.wordpress.android.ui.media;

import android.app.Activity;
import android.app.Fragment;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Toast;

import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.NetworkImageView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.util.ActivityUtils;
import org.wordpress.android.util.ImageUtils.BitmapWorkerCallback;
import org.wordpress.android.util.ImageUtils.BitmapWorkerTask;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.StringUtils;
import org.xmlrpc.android.ApiHelper;

import javax.inject.Inject;

/**
 * A fragment for editing media on the Media tab
 */
public class MediaEditFragment extends Fragment {
    private static final String ARGS_MEDIA_ID = "media_id";
    // also appears in the layouts, from the strings.xml
    public static final String TAG = "MediaEditFragment";
    public static final int MISSING_MEDIA_ID = -1;

    @Inject MediaStore mMediaStore;

    private NetworkImageView mNetworkImageView;
    private ImageView mLocalImageView;
    private EditText mTitleView;
    private EditText mCaptionView;
    private EditText mDescriptionView;

    private String mTitleOriginal;
    private String mDescriptionOriginal;
    private String mCaptionOriginal;

    private MediaEditFragmentCallback mCallback;

    private boolean mIsMediaUpdating = false;

    private long mMediaId = MISSING_MEDIA_ID;
    private ScrollView mScrollView;
    private View mLinearLayout;
    private ImageLoader mImageLoader;

    private SiteModel mSite;

    public interface MediaEditFragmentCallback {
        void onResume(Fragment fragment);
        void setLookClosable();
        void onPause(Fragment fragment);
        void onSavedEdit(long mediaId, boolean result);
    }

    public static MediaEditFragment newInstance(SiteModel site, long mediaId) {
        MediaEditFragment fragment = new MediaEditFragment();
        Bundle args = new Bundle();
        args.putLong(ARGS_MEDIA_ID, mediaId);
        args.putSerializable(WordPress.SITE, site);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        if (savedInstanceState == null) {
            if (getArguments() != null) {
                mSite = (SiteModel) getArguments().getSerializable(WordPress.SITE);
            } else {
                mSite = (SiteModel) getActivity().getIntent().getSerializableExtra(WordPress.SITE);
            }
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }

        if (mSite == null) {
            ToastUtils.showToast(getActivity(), R.string.blog_not_found, ToastUtils.Duration.SHORT);
            getActivity().finish();
        }

        setHasOptionsMenu(true);
        // TODO: We want to inject the image loader in this class instead of using a static field.
        mImageLoader = WordPress.imageLoader;

        // retain this fragment across configuration changes
        setRetainInstance(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mCallback = (MediaEditFragmentCallback) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement "
                                         + MediaEditFragmentCallback.class.getSimpleName());
        }
    }


    @Override
    public void onDetach() {
        super.onDetach();
        // set callback to null so we don't accidentally leak the activity instance
        mCallback = null;
    }

    private boolean hasCallback() {
        return (mCallback != null);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hasCallback()) {
            mCallback.onResume(this);
            mCallback.setLookClosable();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (hasCallback()) {
            mCallback.onPause(this);
        }
    }

    public long getMediaId() {
        if (mMediaId != MISSING_MEDIA_ID) {
            return mMediaId;
        } else if (getArguments() != null) {
            mMediaId = getArguments().getLong(ARGS_MEDIA_ID);
            return mMediaId;
        } else {
            return MISSING_MEDIA_ID;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mScrollView = (ScrollView) inflater.inflate(R.layout.media_edit_fragment, container, false);

        mLinearLayout = mScrollView.findViewById(R.id.media_edit_linear_layout);
        mTitleView = (EditText) mScrollView.findViewById(R.id.media_edit_fragment_title);
        mCaptionView = (EditText) mScrollView.findViewById(R.id.media_edit_fragment_caption);
        mDescriptionView = (EditText) mScrollView.findViewById(R.id.media_edit_fragment_description);
        mLocalImageView = (ImageView) mScrollView.findViewById(R.id.media_edit_fragment_image_local);
        mNetworkImageView = (NetworkImageView) mScrollView.findViewById(R.id.media_edit_fragment_image_network);

        loadMedia(getMediaId());

        return mScrollView;
    }

 @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(WordPress.SITE, mSite);
    }

    public void loadMedia(long mediaId) {
        mMediaId = mediaId;
        if (getActivity() != null && mMediaId != MISSING_MEDIA_ID) {
            MediaModel mediaModel = mMediaStore.getSiteMediaWithId(mSite, mMediaId);
            refreshViews(mediaModel);
        } else {
            refreshViews(null);
        }
    }

    void editMedia() {
        ActivityUtils.hideKeyboard(getActivity());
        final long mediaId = getMediaId();
        final String title = mTitleView.getText().toString();
        final String description = mDescriptionView.getText().toString();
        final String caption = mCaptionView.getText().toString();

        ApiHelper.EditMediaItemTask task = new ApiHelper.EditMediaItemTask(mSite, String.valueOf(mediaId), title,
                description, caption,
                new ApiHelper.GenericCallback() {
                    @Override
                    public void onSuccess() {
                        String blogId = String.valueOf(mSite.getId());
                        WordPress.wpDB.updateMediaFile(blogId, String.valueOf(mediaId), title, description, caption);
                        if (getActivity() != null) {
                            Toast.makeText(getActivity(), R.string.media_edit_success, Toast.LENGTH_LONG).show();
                        }
                        mIsMediaUpdating = false;
                        if (hasCallback()) {
                            mCallback.onSavedEdit(mediaId, true);
                        }
                    }

                    @Override
                    public void onFailure(ApiHelper.ErrorType errorType, String errorMessage, Throwable throwable) {
                        if (getActivity() != null) {
                            Toast.makeText(getActivity(), R.string.media_edit_failure, Toast.LENGTH_LONG).show();
                            getActivity().invalidateOptionsMenu();
                        }
                        mIsMediaUpdating = false;
                        if (hasCallback()) {
                            mCallback.onSavedEdit(mediaId, false);
                        }
                    }
                }
        );

        if (!mIsMediaUpdating) {
            mIsMediaUpdating = true;
            task.execute();
        }
    }

    private void setMediaUpdating(boolean isUpdating) {
        mIsMediaUpdating = isUpdating;
    }

    private boolean isMediaUpdating() {
        return mIsMediaUpdating;
    }

    private void refreshImageView(MediaModel mediaModel, boolean isLocal) {
        final String imageUri;
        if (isLocal) {
            imageUri = mediaModel.getFilePath();
        } else {
            imageUri = mediaModel.getUrl();
        }
        if (MediaUtils.isValidImage(imageUri)) {
            int width = mediaModel.getWidth();
            int height = mediaModel.getHeight();

            // differentiating between tablet and phone
            float screenWidth;
            if (this.isInLayout()) {
                screenWidth = mLinearLayout.getMeasuredWidth();
            } else {
                screenWidth = getActivity().getResources().getDisplayMetrics().widthPixels;
            }
            float screenHeight = getActivity().getResources().getDisplayMetrics().heightPixels;

            if (width > screenWidth) {
                height = (int) (height / (width / screenWidth));
            } else if (height > screenHeight) {
                width = (int) (width / (height / screenHeight));
            }

            if (isLocal) {
                loadLocalImage(mLocalImageView, imageUri, width, height);
                mLocalImageView.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
            } else {
                mNetworkImageView.setImageUrl(imageUri + "?w=" + screenWidth, mImageLoader);
                mNetworkImageView.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
            }
        } else {
            mNetworkImageView.setVisibility(View.GONE);
            mLocalImageView.setVisibility(View.GONE);
        }
    }

    private void refreshViews(MediaModel mediaModel) {
        if (mediaModel == null) {
            mLinearLayout.setVisibility(View.GONE);
            return;
        }

        mLinearLayout.setVisibility(View.VISIBLE);

        mScrollView.scrollTo(0, 0);

        boolean isLocal = MediaUtils.isLocalFile(mediaModel.getUploadState());
        if (isLocal) {
            mNetworkImageView.setVisibility(View.GONE);
            mLocalImageView.setVisibility(View.VISIBLE);
        } else {
            mNetworkImageView.setVisibility(View.VISIBLE);
            mLocalImageView.setVisibility(View.GONE);
        }

        // user can't edit local files
        mTitleView.setEnabled(!isLocal);
        mCaptionView.setEnabled(!isLocal);
        mDescriptionView.setEnabled(!isLocal);

        mTitleOriginal = mTitleView.getText().toString();
        mCaptionOriginal = mCaptionView.getText().toString();
        mDescriptionOriginal = mDescriptionView.getText().toString();

        mTitleView.setText(mediaModel.getTitle());
        mTitleView.requestFocus();
        mTitleView.setSelection(mTitleView.getText().length());
        mCaptionView.setText(mediaModel.getCaption());
        mDescriptionView.setText(mediaModel.getDescription());

        refreshImageView(mediaModel, isLocal);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!isInLayout()) {
            inflater.inflate(R.menu.media_edit, menu);
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (!isInLayout()) {
            menu.findItem(R.id.menu_new_media).setVisible(false);
            menu.findItem(R.id.menu_search).setVisible(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_save_media) {
            item.setActionView(R.layout.progressbar);
            editMedia();
        }
        return super.onOptionsItemSelected(item);
    }

    private synchronized void loadLocalImage(ImageView imageView, String filePath, int width, int height) {
        if (MediaUtils.isValidImage(filePath)) {
            imageView.setTag(filePath);

            Bitmap bitmap = WordPress.getBitmapCache().get(filePath);
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            } else {
                BitmapWorkerTask task = new BitmapWorkerTask(imageView, width, height, new BitmapWorkerCallback() {
                    @Override
                    public void onBitmapReady(String path, ImageView imageView, Bitmap bitmap) {
                        if (imageView != null) {
                            imageView.setImageBitmap(bitmap);
                        }
                        WordPress.getBitmapCache().put(path, bitmap);
                    }
                });
                task.execute(filePath);
            }
        }
    }

    public boolean isDirty() {
        return mMediaId != MISSING_MEDIA_ID &&
                (!StringUtils.equals(mTitleOriginal, mTitleView.getText().toString())
                || !StringUtils.equals(mCaptionOriginal, mCaptionView.getText().toString())
                || !StringUtils.equals(mDescriptionOriginal, mDescriptionView.getText().toString()));
    }
}
