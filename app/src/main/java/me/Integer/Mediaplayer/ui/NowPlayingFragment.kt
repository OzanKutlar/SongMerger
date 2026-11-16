package me.Integer.Mediaplayer.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import me.Integer.Mediaplayer.R

/** The full-screen player. All behaviour lives in [NowPlayingController]. */
class NowPlayingFragment : Fragment(R.layout.fragment_now_playing), PlayerHost.Listener {

    private var controller: NowPlayingController? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = NowPlayingController(view, requireActivity() as PlayerHost)
        view.findViewById<View>(R.id.playerClose).setOnClickListener { (requireActivity() as Navigator).goBack() }
    }

    override fun onStart() {
        super.onStart()
        (requireActivity() as PlayerHost).addPlayerListener(this)
        controller?.start()
    }

    override fun onStop() {
        controller?.stop()
        (requireActivity() as PlayerHost).removePlayerListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        controller = null
        super.onDestroyView()
    }

    override fun onPlayerChanged() {
        controller?.onPlayerChanged()
    }
}
