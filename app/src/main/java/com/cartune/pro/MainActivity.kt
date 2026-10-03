package com.cartune.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cartune.pro.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMainBinding
    private var locationManager: LocationManager? = null

    // GPS permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            startGPSUpdates()
        } else {
            Toast.makeText(this, "Нужен GPS доступ для замера скорости машины!", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark OLED Fullscreen
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyImmersive()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupWebView()
        checkAndRequestGPS()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.setBackgroundColor(Color.parseColor("#030305"))

        wv.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
        }

        wv.loadUrl("file:///android_asset/index.html")
    }

    private fun checkAndRequestGPS() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            startGPSUpdates()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGPSUpdates() {
        try {
            // High precision GPS satellite updates (minTime: 50ms, minDistance: 0m)
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                50L,
                0f,
                this
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(loc: Location) {
        // loc.speed is in meters/second -> convert to km/h
        val speedKmh = loc.speed * 3.6f
        val accuracy = loc.accuracy
        val altitude = loc.altitude

        runOnUiThread {
            // Push real satellite speed directly into the live race stopwatch
            val js = "window.updateGPSSpeed($speedKmh, $accuracy, $altitude);"
            binding.webView.evaluateJavascript(js, null)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        binding.webView.onResume()
        startGPSUpdates()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        locationManager?.removeUpdates(this)
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(this)
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }
}
