package com.goodwy.audiobook.features.bookPlaying

import android.os.Bundle
import android.view.GestureDetector
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.forEach
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.squareup.picasso.Picasso
import com.goodwy.audiobook.R
import com.goodwy.audiobook.databinding.BookPlayBinding
import com.goodwy.audiobook.features.ViewBindingController
import com.goodwy.audiobook.features.audio.LoudnessDialog
import com.goodwy.audiobook.features.bookPlaying.selectchapter.SelectChapterDialog
import com.goodwy.audiobook.features.bookmarks.BookmarkController
import com.goodwy.audiobook.features.settings.SettingsController
import com.goodwy.audiobook.features.settings.dialogs.PlaybackSpeedDialogController
import com.goodwy.audiobook.injection.appComponent
import com.goodwy.audiobook.misc.CircleOutlineProvider
import com.goodwy.audiobook.misc.conductor.asTransaction
import com.goodwy.audiobook.misc.conductor.clearAfterDestroyView
import com.goodwy.audiobook.misc.formatTime
import com.goodwy.audiobook.misc.getUUID
import com.goodwy.audiobook.misc.putUUID
import com.goodwy.audiobook.playback.player.Equalizer
import com.goodwy.audiobook.common.pref.PrefKeys
import com.goodwy.audiobook.uitools.PlayPauseDrawableSetter
import de.paulwoitaschek.flowpref.Pref
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import kotlin.time.Duration


private const val NI_BOOK_ID = "niBookId"

/**
 * Base class for book playing interaction.
 */
class BookPlayController(bundle: Bundle) : ViewBindingController<BookPlayBinding>(bundle, BookPlayBinding::inflate) {

  constructor(bookId: UUID) : this(Bundle().apply { putUUID(NI_BOOK_ID, bookId) })

  @Inject
  lateinit var equalizer: Equalizer
  @Inject
  lateinit var viewModel: BookPlayViewModel

  @field:[Inject Named(PrefKeys.CONTENTS_BUTTON_MODE)]
  lateinit var contentsButtonMode: Pref<Boolean>

  private val bookId = bundle.getUUID(NI_BOOK_ID)
  private var coverLoaded = false

  private var sleepTimerItem: MenuItem by clearAfterDestroyView()
  private var skipSilenceItem: MenuItem by clearAfterDestroyView()

  private var playPauseDrawableSetter by clearAfterDestroyView<PlayPauseDrawableSetter>()

  init {
    appComponent.inject(this)
    this.viewModel.bookId = bookId
  }

  override fun BookPlayBinding.onBindingCreated() {
    coverLoaded = false
    playPauseDrawableSetter = PlayPauseDrawableSetter(play)
    setupClicks()
    setupSlider()
    setupToolbar()
    play.apply {
      outlineProvider = CircleOutlineProvider()
      clipToOutline = true
    }
    rewind.apply {
      outlineProvider = CircleOutlineProvider()
      clipToOutline = true
    }
    fastForward.apply {
      outlineProvider = CircleOutlineProvider()
      clipToOutline = true
    }
    contentList.apply {
      outlineProvider = CircleOutlineProvider()
      clipToOutline = true
    }
  }

  override fun BookPlayBinding.onAttach() {
    lifecycleScope.launch {
      this@BookPlayController.viewModel.viewState().collect {
        this@onAttach.render(it)
      }
    }
    lifecycleScope.launch {
      this@BookPlayController.viewModel.viewEffects.collect {
        handleViewEffect(it)
      }
    }
  }

  private fun handleViewEffect(effect: BookPlayViewEffect) {
    when (effect) {
      BookPlayViewEffect.BookmarkAdded -> {
        Snackbar.make(view!!, R.string.bookmark_added, Snackbar.LENGTH_SHORT)
          .show()
      }
      BookPlayViewEffect.ShowSleepTimeDialog -> {
        openSleepTimeDialog()
      }
    }
  }

  private fun BookPlayBinding.render(viewState: BookPlayViewState) {
    Timber.d("render $viewState")
/*    toolbar.title = viewState.title*/ /*Имя книги в заголовке плеера*/
    currentBookText.text = viewState.bookName
    currentChapterText.text = viewState.chapterName
    currentChapterContainer.isVisible = viewState.chapterName != null
    contentList.isVisible = contentsButtonMode.value
    previous.isVisible = viewState.showPreviousNextButtons
    next.isVisible = viewState.showPreviousNextButtons
    playedTime.text = formatTime(viewState.playedTime.toLongMilliseconds(), viewState.duration.toLongMilliseconds())
    maxTime.text = formatTime(viewState.duration.toLongMilliseconds(), viewState.duration.toLongMilliseconds())
    slider.valueTo = viewState.duration.inMilliseconds.toFloat()
    if (!slider.isPressed) {
      slider.value = viewState.playedTime.inMilliseconds.toFloat()
    }
    skipSilenceItem.isChecked = viewState.skipSilence
    playPauseDrawableSetter.setPlaying(viewState.playing)
    showLeftSleepTime(this, viewState.sleepTime)

    if (!coverLoaded) {
      coverLoaded = true
      cover.transitionName = viewState.cover.coverTransitionName()
        val coverFile = viewState.cover.file()
        val placeholder = viewState.cover.placeholder(activity!!)
        if (coverFile == null) {
          Picasso.get().cancelRequest(cover)
          cover.setImageDrawable(placeholder)
        } else {
          Picasso.get().load(coverFile).placeholder(placeholder).into(cover)
      }
    }
  }

  private fun setupClicks() {
    binding.play.setOnClickListener { this.viewModel.playPause() }
    binding.rewind.setOnClickListener { this.viewModel.rewind() }
    binding.fastForward.setOnClickListener { this.viewModel.fastForward() }
    binding.playedTime.setOnClickListener { launchJumpToPositionDialog() }
    binding.previous.setOnClickListener { this.viewModel.previous() }
    binding.next.setOnClickListener { this.viewModel.next() }
    binding.currentChapterContainer.setOnClickListener {
      SelectChapterDialog(bookId).showDialog(router)
    }
    binding.contentList.setOnClickListener {
      SelectChapterDialog(bookId).showDialog(router)
    }

    val detector = GestureDetectorCompat(activity, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDoubleTap(e: MotionEvent?): Boolean {
        viewModel.playPause()
        return true
      }
    })
    binding.cover.isClickable = true
    @Suppress("ClickableViewAccessibility")
    binding.cover.setOnTouchListener { _, event ->
      detector.onTouchEvent(event)
    }
  }

  private fun setupSlider() {
    binding.slider.setLabelFormatter {
      formatTime(it.toLong(), binding.slider.valueTo.toLong())
    }
    binding.slider.addOnChangeListener { slider, value, fromUser ->
      if (isAttached && !fromUser) {
        binding.playedTime.text = formatTime(value.toLong(), slider.valueTo.toLong())
      }
    }
    binding.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) {
      }

      override fun onStopTrackingTouch(slider: Slider) {
        val progress = slider.value.toLong()
        this@BookPlayController.viewModel.seekTo(progress)
      }
    })
  }

  private fun setupToolbar() {
    val menu = binding.toolbar.menu

    sleepTimerItem = menu.findItem(R.id.action_sleep)
    val equalizerItem = menu.findItem(R.id.action_equalizer)
    equalizerItem.isVisible = equalizer.exists

    skipSilenceItem = menu.findItem(R.id.action_skip_silence)

    binding.toolbar.findViewById<View>(R.id.action_bookmark)
      .setOnLongClickListener {
        this.viewModel.addBookmark()
        true
      }

    binding.toolbar.setNavigationOnClickListener { router.popController(this) }
    binding.toolbar.setOnMenuItemClickListener {
      when (it.itemId) {
        R.id.action_settings -> {
          val transaction = SettingsController().asTransaction()
          router.pushController(transaction)
          true
        }
        R.id.action_time_change -> {
          launchJumpToPositionDialog()
          true
        }
        R.id.action_sleep -> {
          this.viewModel.toggleSleepTimer()
          true
        }
        R.id.action_time_lapse -> {
          PlaybackSpeedDialogController().showDialog(router)
          true
        }
        R.id.action_bookmark -> {
          val bookmarkController = BookmarkController(bookId)
            .asTransaction()
          router.pushController(bookmarkController)
          true
        }
        R.id.action_equalizer -> {
          equalizer.launch(activity!!)
          true
        }
        R.id.action_skip_silence -> {
          this.viewModel.toggleSkipSilence()
          true
        }
        R.id.loudness -> {
          LoudnessDialog(bookId).showDialog(router)
          true
        }
        else -> false
      }
    }
  }

  private fun launchJumpToPositionDialog() {
    JumpToPositionDialogController().showDialog(router)
  }

  private fun showLeftSleepTime(binding: BookPlayBinding, duration: Duration) {
    val active = duration > Duration.ZERO
    sleepTimerItem.setIcon(if (active) R.drawable.alarm_off else R.drawable.alarm)
    binding.timerCountdownView.text = formatTime(duration.toLongMilliseconds(), duration.toLongMilliseconds())
    binding.timerCountdownView.isVisible = active
  }

  private fun openSleepTimeDialog() {
    SleepTimerDialogController(bookId)
      .showDialog(router)
  }
}
