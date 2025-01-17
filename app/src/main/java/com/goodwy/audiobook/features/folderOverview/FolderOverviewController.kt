package com.goodwy.audiobook.features.folderOverview

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewAnimationUtils
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.getbase.floatingactionbutton.FloatingActionsMenu
import com.goodwy.audiobook.R
import com.goodwy.audiobook.common.pref.PrefKeys
import com.goodwy.audiobook.databinding.FolderOverviewBinding
import com.goodwy.audiobook.features.folderChooser.FolderChooserActivity
import com.goodwy.audiobook.injection.appComponent
import com.goodwy.audiobook.misc.conductor.context
import com.goodwy.audiobook.mvp.MvpController
import de.paulwoitaschek.flowpref.Pref
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.max

private const val SI_BACKGROUND_VISIBILITY = "si#overlayVisibility"

/**
 * Controller that lets the user add, edit or remove the set audio book folders.
 */
class FolderOverviewController :
  MvpController<FolderOverviewController, FolderOverviewPresenter, FolderOverviewBinding>(FolderOverviewBinding::inflate) {

  @field:[Inject Named(PrefKeys.PADDING)]
  lateinit var paddingPref: Pref<String>

  init {
    appComponent.inject(this)
  }

  override fun createPresenter(): FolderOverviewPresenter = FolderOverviewPresenter()

  override fun FolderOverviewBinding.onBindingCreated() {
    buttonRepresentingTheFam = binding.root.findViewById(R.id.fab_expand_menu_button)

    addAsSingle.setOnClickListener {
      startFolderChooserActivity(FolderChooserActivity.OperationMode.SINGLE_BOOK)
    }
    addAsLibrary.setOnClickListener {
      startFolderChooserActivity(FolderChooserActivity.OperationMode.COLLECTION_BOOK)
    }
    addAsAllLibrary.setOnClickListener {
      startFolderChooserActivity(FolderChooserActivity.OperationMode.LIBRARY_BOOK)
    }

    overlay.isInvisible = true

    overlay.setOnClickListener {
      fam.collapse()
    }

    // preparing list
    val layoutManager = LinearLayoutManager(activity)
    recycler.layoutManager = layoutManager
   /* recycler.addItemDecoration(
        DividerItemDecoration(activity, DividerItemDecoration.VERTICAL)
    )*/

    adapter = FolderOverviewAdapter { toDelete ->
      val toDeleteName = toDelete.folder
      MaterialDialog(activity!!).show {
        title(R.string.delete_folder)
		cornerRadius(4f)
        message(text = "${context.getString(R.string.delete_folder_content)}\n$toDeleteName")
        positiveButton(R.string.remove) { presenter.removeFolder(toDelete) }
        negativeButton(R.string.dialog_cancel)
      }
    }
    recycler.adapter = adapter

    fam.setOnFloatingActionsMenuUpdateListener(famMenuListener)

    addAsSingle.setIconDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_folder)!!.tinted(Color.WHITE))
    addAsLibrary.setIconDrawable(AppCompatResources.getDrawable(context, R.drawable.folder_multiple)!!.tinted(Color.WHITE))
    addAsAllLibrary.setIconDrawable(AppCompatResources.getDrawable(context, R.drawable.folder_libraries)!!.tinted(Color.WHITE))
    addAsSingle.title =
      "${context.getString(R.string.folder_add_single_book)}\n${context.getString(R.string.for_example)} Sherlock Holmes"
    addAsLibrary.title = "${context.getString(R.string.folder_add_collection)}\n${context.getString(R.string.for_example)} Audiobook Folders" +
      "\n/Sherlock Holmes"
    addAsAllLibrary.title = "${context.getString(R.string.folder_add_library_book)}\n${context.getString(R.string.for_example)} Audiobook Folders" +
      "\n/Conan Doyle/Sherlock Holmes"

    setupToolbar()
  }

  override fun FolderOverviewBinding.onAttach() {
    //padding for Edge-to-edge
    lifecycleScope.launch {
      paddingPref.flow.collect {
        val top = paddingPref.value.substringBefore(';').toInt()
        val bottom = paddingPref.value.substringAfter(';').substringBefore(';').toInt()
        val left = paddingPref.value.substringBeforeLast(';').substringAfterLast(';').toInt()
        val right = paddingPref.value.substringAfterLast(';').toInt()
        root.setPadding(left, top, right, bottom)
      }
    }
  }

  private fun setupToolbar() {
    binding.toolbar.setNavigationOnClickListener { activity!!.onBackPressed() }
  }

  override fun provideView() = this

  private lateinit var buttonRepresentingTheFam: View

  private lateinit var adapter: FolderOverviewAdapter

  private val famMenuListener = object : FloatingActionsMenu.OnFloatingActionsMenuUpdateListener {

    private val famCenter = Point()

    override fun onMenuExpanded() {
      binding.getFamCenter(famCenter)

      // get the final radius for the clipping circle
      val finalRadius = max(binding.overlay.width, binding.overlay.height)

      // create the animator for this view (the start radius is zero)
      val anim = ViewAnimationUtils.createCircularReveal(
        binding.overlay,
          famCenter.x, famCenter.y, 0f, finalRadius.toFloat()
      )

      // make the view visible and start the animation
      binding.overlay.isVisible = true
      anim.start()
    }

    override fun onMenuCollapsed() {
      // get the center for the clipping circle
      binding.getFamCenter(famCenter)

      // get the initial radius for the clipping circle
      val initialRadius = max(binding.overlay.height, binding.overlay.width)

      // create the animation (the final radius is zero)
      val anim = ViewAnimationUtils.createCircularReveal(
        binding.overlay,
          famCenter.x, famCenter.y, initialRadius.toFloat(), 0f
      )

      // make the view invisible when the animation is done
      anim.addListener(
          object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
              super.onAnimationEnd(animation)
              binding.overlay.isInvisible = true
            }
          }
      )

      // start the animation
      anim.start()
    }
  }

  /**
   * Calculates the point representing the center of the floating action menus button. Note, that
   * the fam is only a container, so we have to calculate the point relatively.
   */
  private fun FolderOverviewBinding.getFamCenter(point: Point) {
    val x = fam.left + ((buttonRepresentingTheFam.left + buttonRepresentingTheFam.right) / 2)
    val y = fam.top + ((buttonRepresentingTheFam.top + buttonRepresentingTheFam.bottom) / 2)
    point.set(x, y)
  }

  override fun onRestoreViewState(view: View, savedViewState: Bundle) {
    // restoring overlay
    binding.overlay.visibility = savedViewState.getInt(SI_BACKGROUND_VISIBILITY)
  }

  private fun FolderOverviewBinding.startFolderChooserActivity(operationMode: FolderChooserActivity.OperationMode) {
    val intent = FolderChooserActivity.newInstanceIntent(activity!!, operationMode)
    // we don't want our listener be informed.
    fam.setOnFloatingActionsMenuUpdateListener(null)
    fam.collapseImmediately()
    fam.setOnFloatingActionsMenuUpdateListener(famMenuListener)

    overlay.isVisible = false
    startActivity(intent)
  }

  override fun handleBack(): Boolean {
    return if (binding.fam.isExpanded) {
      binding.fam.collapse()
	  true
	} else false
  }

  /** Updates the adapter with new contents. **/
  fun newData(models: Collection<FolderModel>) {
    adapter.newItems(models)
  }

  override fun onSaveViewState(view: View, outState: Bundle) {
    super.onSaveViewState(view, outState)
    outState.putInt(SI_BACKGROUND_VISIBILITY, binding.overlay.visibility)
  }
}

private fun Drawable.tinted(@ColorInt color: Int): Drawable = mutate().apply { setTint(color) }
