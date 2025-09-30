package edu.asu.cse535.contextmonitor.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import edu.asu.cse535.contextmonitor.data.AppDb
import edu.asu.cse535.contextmonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRecord.setOnClickListener {
            startActivity(Intent(this, MeasureActivity::class.java))
        }

        binding.btnDeleteAll.setOnClickListener {
            lifecycleScope.launch {
                AppDb.get(this@MainActivity).sessionDao().deleteAll()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "All data deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
