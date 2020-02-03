package org.wordpress.android.ui.reader.subfilter

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.android.support.DaggerFragment
import org.wordpress.android.R
import org.wordpress.android.ui.reader.ReaderSubsActivity
import org.wordpress.android.ui.reader.subfilter.SubfilterCategory.SITES
import org.wordpress.android.ui.reader.subfilter.SubfilterCategory.TAGS
import org.wordpress.android.ui.reader.subfilter.SubfilterListItem.ItemType
import org.wordpress.android.ui.reader.subfilter.SubfilterListItem.ItemType.SITE
import org.wordpress.android.ui.reader.subfilter.SubfilterListItem.ItemType.TAG
import org.wordpress.android.ui.reader.subfilter.adapters.SubfilterListAdapter
import org.wordpress.android.ui.reader.viewmodels.ReaderPostListViewModel
import org.wordpress.android.ui.utils.UiHelpers
import org.wordpress.android.widgets.WPTextView
import java.lang.ref.WeakReference
import javax.inject.Inject

class SubfilterPageFragment : DaggerFragment() {
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    private lateinit var viewModel: ReaderPostListViewModel
    @Inject lateinit var uiHelpers: UiHelpers
    private lateinit var recyclerView: RecyclerView

    companion object {
        const val CATEGORY_KEY = "category_key"

        fun newInstance(category: SubfilterCategory): SubfilterPageFragment {
            val fragment = SubfilterPageFragment()
            val bundle = Bundle()
            bundle.putSerializable(CATEGORY_KEY, category)
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.add_content_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val category = arguments?.getSerializable(CATEGORY_KEY) as SubfilterCategory

        recyclerView = view.findViewById(R.id.content_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireActivity())
        recyclerView.adapter = SubfilterListAdapter(uiHelpers)

        viewModel = ViewModelProviders.of(requireActivity(), viewModelFactory).get(ReaderPostListViewModel::class.java)

        viewModel.subFilters.observe(this, Observer {
            (recyclerView.adapter as? SubfilterListAdapter)?.let { adapter ->
                val items = it?.filter { it.type == category.type } ?: listOf()
                manageEmptyView(items.isEmpty(), category)
                adapter.update(items)
                viewModel.onUpdateTabTitleCount(category, items.size)
            }
        })
    }

    fun setNestedScrollBehavior(enable: Boolean) {
        if (!isAdded) return

        recyclerView.isNestedScrollingEnabled = enable
    }

    private fun manageEmptyView(isEmpty: Boolean, category: SubfilterCategory) {
        if (!isAdded || view == null) {
            return
        }

        val emptyStateContainer = view?.findViewById<LinearLayout>(R.id.empty_state_container) ?: return

        if (isEmpty) {
            emptyStateContainer.apply {
                visibility = View.VISIBLE
                val title = findViewById<WPTextView>(R.id.title)
                val actionButton = findViewById<Button>(R.id.action_button)
                title.setText(
                        if (category == SITES)
                            R.string.reader_filter_empty_sites_list
                        else
                            R.string.reader_filter_empty_tags_list)
                actionButton.setText(
                        if (category == SITES)
                            R.string.reader_filter_empty_sites_action
                        else
                            R.string.reader_filter_empty_tags_action)
                actionButton.setOnClickListener {
                    viewModel.onBottomSheetActionClicked(
                            if (category == SITES)
                                ReaderSubsActivity.TAB_IDX_FOLLOWED_BLOGS
                            else
                                ReaderSubsActivity.TAB_IDX_FOLLOWED_TAGS)
                }
            }
        } else {
            emptyStateContainer.visibility = View.GONE
        }
    }
}

class SubfilterPagerAdapter(val context: Context, val fm: FragmentManager) : FragmentPagerAdapter(fm) {
    private val filterCategory = listOf(SITES, TAGS)
    private val fragments = mutableMapOf<SubfilterCategory, WeakReference<SubfilterPageFragment>>()

    override fun getCount(): Int = filterCategory.size

    override fun getItem(position: Int): Fragment {
        val fragment = SubfilterPageFragment.newInstance(filterCategory[position])
        fragments[filterCategory[position]] = WeakReference(fragment)
        return fragment
    }

    override fun getPageTitle(position: Int): CharSequence? {
        return context.getString(filterCategory[position].titleRes)
    }

    override fun setPrimaryItem(container: ViewGroup, position: Int, `object`: Any) {
        super.setPrimaryItem(container, position, `object`)
        for (i in 0 until fragments.size) {
            val fragment = fragments[filterCategory[i]]?.get()
            fragment?.setNestedScrollBehavior(i == position)
        }
        container.requestLayout()
    }
}

enum class SubfilterCategory(@StringRes val titleRes: Int, val type: ItemType) {
    SITES(R.string.reader_filter_sites_title, SITE),
    TAGS(R.string.reader_filter_tags_title, TAG)
}
