package com.example.fingerprint_identifier

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.fingerprint_identifier.databinding.ActivityMainBinding
import com.example.fingerprint_identifier.ui.camera.CameraFragment
import com.example.fingerprint_identifier.ui.home.HomeFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }
}


