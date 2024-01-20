package me.devsaki.hentoid.fragments.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.ISelectionListener
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.drag.ItemTouchCallback
import com.mikepenz.fastadapter.drag.SimpleDragCallback
import com.mikepenz.fastadapter.select.SelectExtension
import com.mikepenz.fastadapter.utils.DragDropUtil.onMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.devsaki.hentoid.R
import me.devsaki.hentoid.core.HentoidApp
import me.devsaki.hentoid.database.CollectionDAO
import me.devsaki.hentoid.database.ObjectBoxDAO
import me.devsaki.hentoid.database.domains.SiteBookmark
import me.devsaki.hentoid.enums.Site
import me.devsaki.hentoid.ui.InputDialog.invokeInputDialog
import me.devsaki.hentoid.util.Helper
import me.devsaki.hentoid.util.ToastHelper
import me.devsaki.hentoid.viewholders.IDraggableViewHolder
import me.devsaki.hentoid.viewholders.TextItem
import me.devsaki.hentoid.widget.FastAdapterPreClickSelectHelper

class BookmarksDialogFragment : DialogFragment(), ItemTouchCallback {

    companion object {
        private const val KEY_SITE = "site"
        private const val KEY_TITLE = "title"
        private const val KEY_URL = "url"

        fun invoke(
            parent: FragmentActivity,
            site: Site,
            title: String,
            url: String
        ) {
            val fragment = BookmarksDialogFragment()
            val args = Bundle()
            args.putInt(KEY_SITE, site.code)
            args.putString(KEY_TITLE, title)
            args.putString(KEY_URL, url)
            fragment.arguments = args
            fragment.show(parent.supportFragmentManager, null)
        }
    }

    // === UI
    private var selectionToolbar: Toolbar? = null
    private var editMenu: MenuItem? = null
    private var copyMenu: MenuItem? = null
    private var homeMenu: MenuItem? = null
    private var recyclerView: RecyclerView? = null
    private var bookmarkCurrentBtn: MaterialButton? = null

    private val itemAdapter = ItemAdapter<TextItem<SiteBookmark>>()
    private val fastAdapter = FastAdapter.with(itemAdapter)
    private lateinit var selectExtension: SelectExtension<TextItem<SiteBookmark>>
    private var touchHelper: ItemTouchHelper? = null

    // === VARIABLES
    private var parent: Parent? = null
    private lateinit var site: Site
    private lateinit var title: String
    private lateinit var url: String

    // Bookmark ID of the current webpage
    private var bookmarkId: Long = -1

    // Used to ignore native calls to onBookClick right after that book has been deselected
    private var invalidateNextBookClick = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotNull(arguments) { "No arguments found" }

        arguments?.apply {
            site = Site.searchByCode(getInt(KEY_SITE).toLong())
            title = getString(KEY_TITLE, "")
            url = getString(KEY_URL, "")
        }
        parent = activity as Parent
    }

    override fun onDestroy() {
        parent = null

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao: CollectionDAO = ObjectBoxDAO(HentoidApp.getInstance())
                try {
                    Helper.updateBookmarksJson(HentoidApp.getInstance(), dao)
                } finally {
                    dao.cleanup()
                }
            }
        }

        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_web_bookmarks, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        val homepage =
            ViewCompat.requireViewById<MaterialButton>(rootView, R.id.bookmark_homepage_btn)
        homepage.icon = ContextCompat.getDrawable(requireContext(), site.ico)
        homepage.setOnClickListener { parent?.loadUrl(site.url) }
        val bookmarks = reloadBookmarks()

        // Gets (or creates and attaches if not yet existing) the extension from the given `FastAdapter`
        selectExtension = fastAdapter.getOrCreateExtension(SelectExtension::class.java)!!

        selectExtension.isSelectable = true
        selectExtension.multiSelect = true
        selectExtension.selectOnLongClick = true
        selectExtension.selectWithItemUpdate = true
        selectExtension.selectionListener =
            object : ISelectionListener<TextItem<SiteBookmark>> {
                override fun onSelectionChanged(
                    item: TextItem<SiteBookmark>,
                    selected: Boolean
                ) {
                    onSelectionChanged()
                }
            }
        val helper = FastAdapterPreClickSelectHelper(
            selectExtension
        )
        fastAdapter.onPreClickListener =
            { _, _, _, position: Int -> helper.onPreClickListener(position) }
        fastAdapter.onPreLongClickListener =
            { _, _, _, position: Int -> helper.onPreLongClickListener(position) }

        recyclerView = ViewCompat.requireViewById(rootView, R.id.bookmarks_list)

        // Activate drag & drop
        val dragCallback = SimpleDragCallback(this)
        dragCallback.notifyAllDrops = true
        touchHelper = ItemTouchHelper(dragCallback)
        touchHelper!!.attachToRecyclerView(recyclerView)
        recyclerView!!.adapter = fastAdapter
        fastAdapter.onClickListener =
            { _, _, i: TextItem<SiteBookmark>, _ -> onItemClick(i) }

        fastAdapter.addEventHook(
            TextItem.DragHandlerTouchEvent { position: Int? ->
                val vh =
                    recyclerView!!.findViewHolderForAdapterPosition(
                        position!!
                    )
                if (vh != null) touchHelper!!.startDrag(vh)
            }
        )
        selectionToolbar = ViewCompat.requireViewById(rootView, R.id.toolbar)
        selectionToolbar!!.setOnMenuItemClickListener { menuItem: MenuItem ->
            selectionToolbarOnItemClicked(
                menuItem
            )
        }
        editMenu = selectionToolbar!!.menu.findItem(R.id.action_edit)
        copyMenu = selectionToolbar!!.menu.findItem(R.id.action_copy)
        homeMenu = selectionToolbar!!.menu.findItem(R.id.action_home)
        bookmarkCurrentBtn =
            ViewCompat.requireViewById(rootView, R.id.bookmark_current_btn)
        val currentBookmark = bookmarks.firstOrNull { b: SiteBookmark ->
            SiteBookmark.urlsAreSame(b.url, url)
        }
        if (currentBookmark != null) bookmarkId = currentBookmark.id
        updateBookmarkButton()
    }

    private fun reloadBookmarks(): List<SiteBookmark> {
        val bookmarks: List<SiteBookmark>
        val dao: CollectionDAO = ObjectBoxDAO(requireContext())
        bookmarks = try {
            reloadBookmarks(dao)
        } finally {
            dao.cleanup()
        }
        return bookmarks
    }

    private fun reloadBookmarks(dao: CollectionDAO): List<SiteBookmark> {
        val bookmarks: List<SiteBookmark> = dao.selectBookmarks(site)
        itemAdapter.set(bookmarks.map { b: SiteBookmark ->
            TextItem(
                b.title,
                b,
                draggable = true,
                reformatCase = true,
                isHighlighted = b.isHomepage,
                centered = false,
                touchHelper = touchHelper
            )
        })
        return bookmarks
    }

    /**
     * Callback for any selection change (item added to or removed from selection)
     */
    private fun onSelectionChanged() {
        val selectedCount = selectExtension.selectedItems.size
        if (0 == selectedCount) {
            selectionToolbar!!.visibility = View.GONE
            selectExtension.selectOnLongClick = true
            invalidateNextBookClick = true
            Handler(Looper.getMainLooper()).postDelayed({
                invalidateNextBookClick = false
            }, 200)
        } else {
            editMenu!!.isVisible = 1 == selectedCount
            copyMenu!!.isVisible = 1 == selectedCount
            homeMenu!!.isVisible = 1 == selectedCount
            selectionToolbar!!.visibility = View.VISIBLE
        }
    }

    private fun updateBookmarkButton() {
        bookmarkCurrentBtn?.apply {
            if (bookmarkId > -1) {
                bookmarkCurrentBtn!!.icon = ContextCompat.getDrawable(
                    context,
                    R.drawable.ic_bookmark_full
                )
                setText(R.string.unbookmark_current)
                setOnClickListener { onBookmarkBtnClickedRemove() }
                this@BookmarksDialogFragment.parent!!.updateBookmarkButton(true)
            } else {
                bookmarkCurrentBtn!!.icon = ContextCompat.getDrawable(
                    context,
                    R.drawable.ic_bookmark
                )
                setText(R.string.bookmark_current)
                setOnClickListener { onBookmarkBtnClickedAdd() }
                this@BookmarksDialogFragment.parent!!.updateBookmarkButton(false)
            }
        }
    }

    private fun onBookmarkBtnClickedAdd() {
        val dao: CollectionDAO = ObjectBoxDAO(bookmarkCurrentBtn!!.context)
        try {
            bookmarkId = dao.insertBookmark(SiteBookmark(site, title, url))
            reloadBookmarks(dao)
            fastAdapter.notifyAdapterDataSetChanged()
        } finally {
            dao.cleanup()
        }
        updateBookmarkButton()
    }

    private fun onBookmarkBtnClickedRemove() {
        val dao: CollectionDAO = ObjectBoxDAO(bookmarkCurrentBtn!!.context)
        try {
            dao.deleteBookmark(bookmarkId)
            bookmarkId = -1
            reloadBookmarks(dao)
            fastAdapter.notifyAdapterDataSetChanged()
        } finally {
            dao.cleanup()
        }
        updateBookmarkButton()
    }

    @SuppressLint("NonConstantResourceId")
    private fun selectionToolbarOnItemClicked(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.action_copy -> copySelectedItem()
            R.id.action_edit -> editSelectedItem()
            R.id.action_delete -> purgeSelectedItems()
            R.id.action_home -> toggleHomeSelectedItem()
            else -> {
                selectionToolbar!!.visibility = View.GONE
                return false
            }
        }
        return true
    }

    /**
     * Callback for the "share item" action button
     */
    private fun copySelectedItem() {
        val selectedItems: Set<TextItem<SiteBookmark>> = selectExtension.selectedItems
        val context: Context? = activity
        if (1 == selectedItems.size && context != null) {
            val b = selectedItems.first().getObject()
            if (b != null && Helper.copyPlainTextToClipboard(context, b.url)) {
                ToastHelper.toastShort(context, R.string.web_url_clipboard)
                selectionToolbar?.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * Callback for the "share item" action button
     */
    private fun editSelectedItem() {
        val selectedItems: Set<TextItem<SiteBookmark>> = selectExtension.selectedItems
        if (1 == selectedItems.size) {
            val b = selectedItems.first().getObject()
            if (b != null) invokeInputDialog(
                requireActivity(),
                R.string.bookmark_edit_title,
                b.title,
                { s -> onEditTitle(s) }
            ) { selectExtension.deselect(selectExtension.selections.toMutableSet()) }
        }
    }

    private fun onEditTitle(newTitle: String) {
        val selectedItems: Set<TextItem<SiteBookmark>> = selectExtension.selectedItems
        val context: Context? = activity
        if (1 == selectedItems.size && context != null) {
            val b = selectedItems.first().getObject()
            if (b != null) {
                b.title = newTitle
                val dao: CollectionDAO = ObjectBoxDAO(context)
                try {
                    dao.insertBookmark(b)
                    reloadBookmarks(dao)
                    fastAdapter.notifyAdapterDataSetChanged()
                    selectionToolbar?.visibility = View.INVISIBLE
                } finally {
                    dao.cleanup()
                }
            }
        }
    }

    /**
     * Callback for the "delete item" action button
     */
    private fun purgeSelectedItems() {
        val selectedItems: Set<TextItem<SiteBookmark>> = selectExtension.selectedItems
        val context: Context? = activity
        if (selectedItems.isNotEmpty() && context != null) {
            val selectedContent =
                selectedItems.mapNotNull { obj -> obj.getObject() }
            if (selectedContent.isNotEmpty()) {
                val dao: CollectionDAO = ObjectBoxDAO(context)
                try {
                    for (b in selectedContent) {
                        if (b.id == bookmarkId) {
                            bookmarkId = -1
                            updateBookmarkButton()
                        }
                        dao.deleteBookmark(b.id)
                    }
                    reloadBookmarks(dao)
                    fastAdapter.notifyAdapterDataSetChanged()
                    selectionToolbar?.visibility = View.INVISIBLE
                } finally {
                    dao.cleanup()
                }
            }
        }
    }

    /**
     * Callback for the "toggle as welcome page" action button
     */
    private fun toggleHomeSelectedItem() {
        val selectedItems: Set<TextItem<SiteBookmark>> = selectExtension.selectedItems
        val context: Context? = activity
        if (1 == selectedItems.size && context != null) {
            val selectedContent =
                selectedItems.mapNotNull { obj -> obj.getObject() }
            if (selectedContent.isNotEmpty()) {
                val selectedBookmark = selectedContent[0]
                val dao: CollectionDAO = ObjectBoxDAO(context)
                try {
                    val bookmarks = dao.selectBookmarks(site)
                    for (b in bookmarks) {
                        if (b.id == selectedBookmark.id) b.isHomepage =
                            !b.isHomepage else b.isHomepage =
                            false
                    }
                    dao.insertBookmarks(bookmarks)
                    reloadBookmarks(dao)
                    fastAdapter.notifyAdapterDataSetChanged()
                    selectExtension.selectOnLongClick = true
                    selectExtension.deselect(selectExtension.selections.toMutableSet())
                    selectionToolbar?.visibility = View.INVISIBLE
                } finally {
                    dao.cleanup()
                }
            }
        }
    }

    private fun onItemClick(item: TextItem<SiteBookmark>): Boolean {
        if (selectExtension.selectedItems.isEmpty()) {
            if (!invalidateNextBookClick && item.getObject() != null) {
                parent!!.loadUrl(item.getObject()!!.url)
                dismiss()
            } else invalidateNextBookClick = false
            return true
        } else {
            selectExtension.selectOnLongClick = false
        }
        return false
    }

    override fun itemTouchDropped(oldPosition: Int, newPosition: Int) {
        // Update  visuals
        val vh = recyclerView!!.findViewHolderForAdapterPosition(newPosition)
        if (vh is IDraggableViewHolder) {
            (vh as IDraggableViewHolder).onDropped()
        }

        // Update DB
        if (oldPosition == newPosition) return
        val dao: CollectionDAO = ObjectBoxDAO(requireContext())
        try {
            val bookmarks = dao.selectBookmarks(site)
            if (oldPosition < 0 || oldPosition >= bookmarks.size) return

            // Move the item
            val fromValue = bookmarks[oldPosition]
            val delta = if (oldPosition < newPosition) 1 else -1
            var i = oldPosition
            while (i != newPosition) {
                bookmarks[i] = bookmarks[i + delta]
                i += delta
            }
            bookmarks[newPosition] = fromValue

            // Renumber everything and update the DB
            var index = 1
            for (b in bookmarks) {
                b.order = index++
                dao.insertBookmark(b)
            }
        } finally {
            dao.cleanup()
        }
    }

    override fun itemTouchOnMove(oldPosition: Int, newPosition: Int): Boolean {
        // Update visuals
        onMove(itemAdapter, oldPosition, newPosition) // change position
        return true
    }

    override fun itemTouchStartDrag(viewHolder: RecyclerView.ViewHolder) {
        // Update visuals
        if (viewHolder is IDraggableViewHolder) {
            (viewHolder as IDraggableViewHolder).onDragged()
        }
    }

    override fun itemTouchStopDrag(viewHolder: RecyclerView.ViewHolder) {
        // Nothing
    }

    interface Parent {
        fun loadUrl(url: String)
        fun updateBookmarkButton(newValue: Boolean)
    }
}