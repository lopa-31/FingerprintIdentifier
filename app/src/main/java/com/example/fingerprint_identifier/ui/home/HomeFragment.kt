package com.example.fingerprint_identifier.ui.home

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.example.fingerprint_identifier.R
import com.example.fingerprint_identifier.databinding.FragmentHomeBinding
import com.example.fingerprint_identifier.ui.base.BaseFragment
import com.example.fingerprint_identifier.ui.camera.CameraFragment
import com.example.fingerprint_identifier.ui.camera.Camera2Fragment

class HomeFragment : BaseFragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.registerButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CameraFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.verifyButton.setOnClickListener {
            val cameraFragment = CameraFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("IS_VERIFICATION_MODE", true)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, cameraFragment)
                .addToBackStack(null)
                .commit()
        }

        binding.camera2Button.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, Camera2Fragment())
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 