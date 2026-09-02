package com.v2ray.ang.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

class X7 : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.x7)

        findViewById<Toolbar>(R.id.a0).setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.b0).text = q.a(8)
        findViewById<TextView>(R.id.b1).text = q.a(4)
        findViewById<TextView>(R.id.b2).text = q.a(5)
        findViewById<TextView>(R.id.b3).text = q.a(6)
        findViewById<TextView>(R.id.b4).text = q.a(7)

        findViewById<View>(R.id.a1).setOnClickListener { Utils.openUri(this, q.a(0)) }
        findViewById<View>(R.id.a2).setOnClickListener { Utils.openUri(this, q.a(1)) }
        findViewById<View>(R.id.a3).setOnClickListener { Utils.openUri(this, q.a(2)) }
        findViewById<View>(R.id.a4).setOnClickListener { Utils.openUri(this, q.a(3)) }
    }
}
