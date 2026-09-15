package com.kintokikyo.patchy

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.View

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = View(this)
        view.setBackgroundColor(Color.DKGRAY)

        setContentView(view)
    }
}
