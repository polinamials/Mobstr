package com.example.mobstr

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

class DiagnosticsFragment : Fragment() {
    private val handler = Handler(Looper.getMainLooper())
    private var output: TextView? = null
    private val update = object : Runnable {
        override fun run() {
            output?.text = (activity as? MainActivity)?.streamDiagnostics() ?: "Unavailable"
            if (output != null) handler.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?) =
        inflater.inflate(R.layout.fragment_diagnostics, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        output = view.findViewById(R.id.diagnosticsText)
        handler.post(update)
    }

    override fun onDestroyView() {
        output = null
        handler.removeCallbacks(update)
        super.onDestroyView()
    }
}
