package edu.asu.cse535.contextmonitor.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import edu.asu.cse535.contextmonitor.data.AppDb
import edu.asu.cse535.contextmonitor.data.SessionRecord
import edu.asu.cse535.contextmonitor.databinding.ActivitySymptomsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SymptomsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySymptomsBinding
    private var hr: Int = -1
    private var rr: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySymptomsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hr = intent.getIntExtra("HR", -1)
        rr = intent.getIntExtra("RR", -1)
        if (hr < 0 || rr < 0) {
            Toast.makeText(this, "Missing HR/RR, go back and measure.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        binding.btnUpload.setOnClickListener {
            val record = SessionRecord(
                heartRateBpm = hr,
                respiratoryRateBpm = rr,
                sFever = binding.sliderFever.value.toInt(),
                sCough = binding.sliderCough.value.toInt(),
                sSOB = binding.sliderSob.value.toInt(),
                sFatigue = binding.sliderFatigue.value.toInt(),
                sAches = binding.sliderAches.value.toInt(),
                sHeadache = binding.sliderHeadache.value.toInt(),
                sThroat = binding.sliderThroat.value.toInt(),
                sNasal = binding.sliderNasal.value.toInt(),
                sNausea = binding.sliderNausea.value.toInt(),
                sDiarrhea = binding.sliderDiarrhea.value.toInt()
            )
            lifecycleScope.launch {
                AppDb.get(this@SymptomsActivity).sessionDao().insert(record)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SymptomsActivity, "Saved locally", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}
