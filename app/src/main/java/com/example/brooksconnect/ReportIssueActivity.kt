package com.example.brooksconnect

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class ReportIssueActivity : AppCompatActivity() {

    private lateinit var categories: List<LinearLayout>
    private var selectedCategoryIndex: Int = -1
    private val categoryNames = listOf("Road Damage", "Waste Management", "Street Light", "Drainage", "Noise Complaint", "Other")
    private var currentAiPriority: String = "" // Store AI priority suggestion
    private var currentAiClassification: String = "" // Store AI classification suggestion
    private var currentAiAction: String = "" // Store AI action suggestion
    
    private var selectedImageUri: Uri? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    
    // File Picker
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            
            // Persist permission
            val takeFlags: Int = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Ignore
            }

            // Update UI
            val iconView = findViewById<ImageView>(R.id.upload_preview_icon)
            iconView?.let {
                it.layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                it.layoutParams.height = 400 
                it.setPadding(0, 0, 0, 0)
                
                val type = contentResolver.getType(uri)
                if (type?.startsWith("image/") == true) {
                    it.setImageURI(uri)
                    it.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    it.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                    it.scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            }
        }
    }

    // Location Picker Result
    private val startLocationPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val locationString = data?.getStringExtra("location_string")
            val lat = data?.getDoubleExtra("lat", 0.0)
            val lon = data?.getDoubleExtra("lon", 0.0)
            
            if (locationString != null) {
                // Save format: "Address (Lat, Lon)" so we can parse it later
                val fullLocation = "$locationString ($lat, $lon)"
                findViewById<EditText>(R.id.issue_location).setText(fullLocation)
            }
        }
    }

    private var currentUserName: String = "Anonymous"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report_issue)
        
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        
        // Fetch user name
        val user = auth.currentUser
        if (user != null) {
            db.collection("users").document(user.uid).get().addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                     currentUserName = document.getString("name") ?: user.displayName ?: "Anonymous"
                }
            }
        }
        
        // ... rest of onCreate ...
        // Initialize Cloudinary
        try {
            val config = HashMap<String, String>()
            config["cloud_name"] = "dpj3oe5kg" 
            config["api_key"] = "434136765863741"
            config["api_secret"] = "Z93-mkZLP5jlRAKEfmnipxA-vfc"
            MediaManager.init(this, config)
        } catch (e: Exception) {
            // Already initialized
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.header)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, v.paddingBottom)
            insets
        }

        findViewById<ImageView>(R.id.back_button).setOnClickListener {
            finish()
        }

        // Categories
        val catRoad = findViewById<LinearLayout>(R.id.cat_road)
        val catWaste = findViewById<LinearLayout>(R.id.cat_waste)
        val catLight = findViewById<LinearLayout>(R.id.cat_light)
        val catDrainage = findViewById<LinearLayout>(R.id.cat_drainage)
        val catNoise = findViewById<LinearLayout>(R.id.cat_noise)
        val catOther = findViewById<LinearLayout>(R.id.cat_other)

        categories = listOf(catRoad, catWaste, catLight, catDrainage, catNoise, catOther)

        categories.forEachIndexed { index, layout ->
            layout.setOnClickListener {
                selectCategory(index)
            }
        }
        
        findViewById<LinearLayout>(R.id.add_photo_area).setOnClickListener {
            pickFileLauncher.launch(arrayOf("image/*", "application/pdf"))
        }


        // Location Click Listener - Handle drawable start click
        findViewById<EditText>(R.id.issue_location).setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val drawableStart = (v as EditText).compoundDrawablesRelative[0]
                if (drawableStart != null) {
                    // Check if touch is within the bounds of the drawable start
                    // Use event.x which is relative to the view
                    val bounds = drawableStart.bounds.width() + v.paddingStart + 50 
                    if (event.x <= bounds) {
                        startLocationPicker.launch(Intent(this, LocationPickerActivity::class.java))
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        findViewById<MaterialButton>(R.id.submit_button).setOnClickListener {
            submitReport()
        }
        
        // --- AI Smart Categorization Setup ---
        setupSmartCategorization()
    }
    
    // AI Components
    private val GEMINI_API_KEY = "AIzaSyDv7pG4Cw9Senb47djsqRAvx368bfAzqFM" // Using same key as Analytics
    private lateinit var generativeModel: GenerativeModel
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    
    private fun setupSmartCategorization() {
        // Init Gemini with Safety Settings
        val harassmentSafety = SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH)
        val hateSpeechSafety = SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH)
        
        generativeModel = GenerativeModel(
            modelName = "gemini-pro", // Reverting to stable Pro model
            apiKey = GEMINI_API_KEY,
            safetySettings = listOf(harassmentSafety, hateSpeechSafety)
        )
        
        val descriptionInput = findViewById<EditText>(R.id.issue_description)
        // ... (rest same)
        val suggestionLayout = findViewById<LinearLayout>(R.id.ai_suggestion_layout)
        val applyButton = findViewById<TextView>(R.id.apply_suggestion_button)
        // ...
        
        // Text Watcher with Debounce 
        descriptionInput.addTextChangedListener(object : TextWatcher {
             override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
             override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                 suggestionLayout.visibility = View.GONE
                 debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
             }
             override fun afterTextChanged(s: Editable?) {
                 val text = s?.toString()?.trim() ?: ""
                 if (text.length > 5) {
                     debounceRunnable = Runnable { analyzeDescription(text) }
                     debounceHandler.postDelayed(debounceRunnable!!, 1500)
                 }
             }
        })
        
        applyButton.setOnClickListener {
             // ... (same apply logic)
            val suggestedCategory = findViewById<TextView>(R.id.suggested_category_text).text.toString()
            val index = categoryNames.indexOf(suggestedCategory)
            if (index != -1) {
                selectCategory(index)
                suggestionLayout.visibility = View.GONE
                Toast.makeText(this, "Category applied: $suggestedCategory", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun analyzeDescription(text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Client-side Heuristics for Invalid Input
                val trimmed = text.trim()
                val isGibberish = when {
                    trimmed.length > 20 && !trimmed.contains(" ") -> true // Very long single word (likely mash)
                    trimmed.all { !it.isLetterOrDigit() } -> true // All symbols
                    trimmed.length < 3 -> false // Very short checks
                    else -> false
                }
                
                if (isGibberish) {
                    withContext(Dispatchers.Main) {
                        val suggestionLayout = findViewById<LinearLayout>(R.id.ai_suggestion_layout)
                        val suggestionText = findViewById<TextView>(R.id.suggested_category_text)
                        val applyButton = findViewById<TextView>(R.id.apply_suggestion_button)
                        
                        suggestionText.text = "Please describe clearly"
                        suggestionText.setTextColor(Color.RED)
                        applyButton.visibility = View.GONE
                        suggestionLayout.visibility = View.VISIBLE
                    }
                    return@launch
                }

                val prompt = """
                    Analyze this issue description: "$text"
                    The text may be in English, Filipino (Tagalog), or Taglish.
                    Classify it into exactly one of these (English) categories: 
                    ${categoryNames.joinToString(", ")}
                    
                    Also, suggest a priority level based on urgency. STRICTLY choose from: Low, Medium, or High.
                    - "High": Safety threats, violence (fights), fire, accidents, blocked access, or severe damage.
                    - "Medium": Significant nuisance, waste accumulation, broken lights, functionality issues.
                    - "Low": Minor cosmetic issues, non-urgent noise, small debris.
                    
                    Finally, suggest a short 2-3 word Action for staff, such as:
                    "Maintenance Required", "Cleanup Needed", "Inspection Needed", "Repair Needed", "No Action", "Verify Report", "Call Police", "Call Ambulance".
                    
                    Return ONLY the category name, priority, and action separated by a pipe (|).
                    Example: "Road Damage|High|Repair Needed" or "Waste Management|Medium|Cleanup Needed".
                    
                    If the input describes a valid issue but doesn't fit a category, return "Other|Medium|Inspection Needed".
                    If the input is gibberish (e.g. "safasf", "asdf"), random characters, or too short to be meaningful, return "Invalid".
                """.trimIndent()
                
                val response = generativeModel.generateContent(prompt)
                val rawResponse = response.text?.trim() ?: "Other|Normal|Verify Report"
                
                // DEBUG: Show Toast
                withContext(Dispatchers.Main) {
                   Toast.makeText(this@ReportIssueActivity, "AI Online: Analysis Complete", Toast.LENGTH_SHORT).show()
                }

                parseAiResponse(rawResponse, text)
            } catch (e: Exception) {
                e.printStackTrace()
                // Gemini Failed, Try Groq
                callGroqAi(text)
            }
        }
    }


    private val client = okhttp3.OkHttpClient()

    private suspend fun callGroqAi(text: String) {
        try {
            val prompt = """
                Analyze this issue description: "$text"
                Classify into one of: ${categoryNames.joinToString(", ")}.
                Suggest Priority: Low, Medium, High.
                Suggest Action (2-3 words).
                Return ONLY: Category|Priority|Action
            """.trimIndent()

            val jsonBody = """
                {
                    "model": "llama3-8b-8192",
                    "messages": [
                        {"role": "user", "content": "$prompt"}
                    ]
                }
            """.trimIndent()

            val request = okhttp3.Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $GROQ_API_KEY")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), jsonBody))
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (!response.isSuccessful) throw Exception("Groq Error: ${response.code}")

            val responseBody = response.body?.string() ?: ""
            // Simple JSON parsing
            val content = responseBody.substringAfter("\"content\": \"").substringBefore("\"")
                .replace("\\n", "").replace("\\", "") 

            withContext(Dispatchers.Main) {
               Toast.makeText(this@ReportIssueActivity, "AI Online: Groq (Fallback)", Toast.LENGTH_SHORT).show()
            }
            
            parseAiResponse(content, text)

        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
               val errorMsg = e.message ?: e.toString()
               Toast.makeText(this@ReportIssueActivity, "AI Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
            performLocalFallback(text)
        }
    }

    private suspend fun parseAiResponse(rawResponse: String, originalText: String) {
        // Parse "Category|Priority|Action"
        val parts = rawResponse.split("|")
        var suggestedCategory = parts.getOrElse(0) { "Other" }.trim()
        val suggestedPriority = parts.getOrElse(1) { "Normal" }.trim()
        var suggestedAction = parts.getOrElse(2) { "Verify Report" }.trim()
        
        // Store priority and action
        currentAiPriority = suggestedPriority
        currentAiAction = suggestedAction
        
        // Normalization fixes
        if (suggestedCategory.equals("Road Issue", ignoreCase = true)) suggestedCategory = "Road Damage"

        // Logic: If AI returns "Other", check if Fallback has a better idea
        if (suggestedCategory.equals("Other", ignoreCase = true)) {
            val fallbackCategory = getFallbackCategory(originalText)
            if (fallbackCategory != "Other") {
                suggestedCategory = fallbackCategory
            }
        }

        if (categoryNames.any { it.equals(suggestedCategory, ignoreCase = true) }) {
            withContext(Dispatchers.Main) {
                // Update UI
                val suggestionText = findViewById<TextView>(R.id.suggested_category_text)
                val applyButton = findViewById<TextView>(R.id.apply_suggestion_button)
                suggestionText.setTextColor(ContextCompat.getColor(this@ReportIssueActivity, R.color.purple_500)) 
                applyButton.visibility = View.VISIBLE
                updateSuggestionUI(suggestedCategory)
            }
        } else {
            performLocalFallback(originalText)
        }
    }
    
    // Fallback logic when AI is offline or fails
    private fun performLocalFallback(text: String) {
        val category = getFallbackCategory(text)
        
        // Heuristic Priority & Action
        val lowerText = text.lowercase()
        var priority = "Normal"
        var action = "Verify Report"
        
        when {
            // High Priority / Urgent
            lowerText.contains("fight") || lowerText.contains("accident") || lowerText.contains("fire") || 
            lowerText.contains("danger") || lowerText.contains("injury") || lowerText.contains("blood") ||
            lowerText.contains("gun") || lowerText.contains("weapon") || lowerText.contains("patay") || 
            lowerText.contains("sunog") || lowerText.contains("away") -> {
                priority = "High"
                action = "Call Police/Ambulance"
            }
            
            // Medium Priority
            lowerText.contains("blocked") || lowerText.contains("broken") || lowerText.contains("flood") ||
            lowerText.contains("baha") || lowerText.contains("sira") -> {
                priority = "Medium"
                action = "Maintenance Needed"
            }
            
            else -> {
                priority = "Low"
                action = "Verify Report"
            }
        }
        
        currentAiPriority = priority
        currentAiAction = action
        
        // ALWAYS show suggestion
        CoroutineScope(Dispatchers.Main).launch {
            updateSuggestionUI(category)
        }
    }

    private fun getFallbackCategory(text: String): String {
        val lowerText = text.lowercase()
        var category = "Other"
        
        // Helper regex function for whole word matching
        fun String.hasWord(word: String): Boolean {
            return Regex("\\b$word\\b").containsMatchIn(this)
        }
        
        when {
             // ... existing matches ...
             // Road: English + Filipino (lubak, sira, bitak, daan)
             lowerText.contains("road") || lowerText.contains("pothole") || lowerText.contains("crack") || lowerText.contains("asphalt") ||
             lowerText.contains("lubak") || lowerText.contains("sira") || lowerText.contains("bitak") || lowerText.hasWord("daan") -> category = "Road Damage"
             
             // Waste: English + Filipino (basura, kalat, mabaho, tapon)
             lowerText.contains("trash") || lowerText.contains("garbage") || lowerText.contains("waste") || lowerText.contains("dump") || lowerText.contains("rubbish") ||
             lowerText.contains("basura") || lowerText.contains("kalat") || lowerText.contains("mabaho") || lowerText.contains("dumi") || lowerText.contains("tambak") -> category = "Waste Management"
             
             // Light: English + Filipino (ilaw, poste, madilim, pundi)
             lowerText.contains("light") || lowerText.contains("lamp") || lowerText.contains("dark") || lowerText.contains("bulb") ||
             lowerText.contains("ilaw") || lowerText.contains("poste") || lowerText.contains("madilim") || lowerText.contains("pundi") || lowerText.contains("dilim") -> category = "Street Light"
             
             // Drainage: English + Filipino (baha, bara, tubig, kanal)
             lowerText.contains("drain") || lowerText.contains("flood") || lowerText.contains("clog") || lowerText.contains("water") || lowerText.contains("leak") ||
             lowerText.hasWord("baha") || lowerText.hasWord("bara") || lowerText.contains("tubig") || lowerText.contains("kanal") -> category = "Drainage"
             
             // Noise: English + Filipino (ingay, videoke, sigaw)
             lowerText.contains("noise") || lowerText.contains("loud") || lowerText.contains("music") || lowerText.contains("karaoke") || lowerText.contains("shouting") ||
             lowerText.contains("ingay") || lowerText.contains("videoke") || lowerText.contains("sigaw") || lowerText.contains("kanta") -> category = "Noise Complaint"
        }
        return category
    }
    
    private fun updateSuggestionUI(category: String) {
        val suggestionLayout = findViewById<LinearLayout>(R.id.ai_suggestion_layout)
        val suggestionText = findViewById<TextView>(R.id.suggested_category_text)
        
        // Match exact casing from list
        val finalCategory = categoryNames.find { it.equals(category, ignoreCase = true) } ?: category
        
        
        suggestionText.text = finalCategory
        suggestionLayout.visibility = View.VISIBLE
        
        // Save for submission
        currentAiClassification = finalCategory
    }

    private fun submitReport() {
        val currentUser = auth.currentUser
        
        if (currentUser == null) {
            Toast.makeText(this, "Please log in to submit a report", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedCategoryIndex == -1) {
            Toast.makeText(this, "Please select a category", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Optional: Validate description
        val description = findViewById<EditText>(R.id.issue_description).text.toString().trim()
        if (description.isEmpty()) {
             Toast.makeText(this, "Please describe the issue", Toast.LENGTH_SHORT).show()
             return
        }
        
        val location = findViewById<EditText>(R.id.issue_location).text.toString().trim()

        val submitButton = findViewById<MaterialButton>(R.id.submit_button)
        submitButton.isEnabled = false
        submitButton.text = "Submitting..."
        
        
        if (selectedImageUri != null) {
            uploadImageAndSaveReport(currentUser.uid, currentUser.email ?: "", currentUserName, categoryNames[selectedCategoryIndex], description, location, currentAiPriority, currentAiClassification, currentAiAction)
        } else {
            saveReportToFirestore(currentUser.uid, currentUser.email ?: "", currentUserName, categoryNames[selectedCategoryIndex], description, location, emptyList(), currentAiPriority, currentAiClassification, currentAiAction)
        }
    }
    
    private fun uploadImageAndSaveReport(userId: String, email: String, name: String, category: String, description: String, location: String, aiPriority: String, aiClassification: String, aiAction: String) {
        MediaManager.get().upload(selectedImageUri)
            .unsigned("ml_default")
            .option("resource_type", "auto")
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {
                    runOnUiThread {
                        Toast.makeText(this@ReportIssueActivity, "Uploading image...", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) { }
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as? String ?: ""
                    saveReportToFirestore(userId, email, name, category, description, location, listOf(url), aiPriority, aiClassification, aiAction)
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    runOnUiThread {
                        val submitButton = findViewById<MaterialButton>(R.id.submit_button)
                        submitButton.isEnabled = true
                        submitButton.text = "Submit Report"
                        Toast.makeText(this@ReportIssueActivity, "Upload failed: ${error.description}", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {}
            })
            .dispatch()
    }

    private fun saveReportToFirestore(userId: String, email: String, name: String, category: String, description: String, location: String, attachments: List<String>, aiPriority: String, aiClassification: String, aiAction: String) {
        val report = hashMapOf(
            "userId" to userId,
            "category" to category,
            "status" to "received",
            "createdAt" to System.currentTimeMillis(),
            "userEmail" to email,
            "reporterName" to name,
            "description" to description,
            "location" to location,
            "attachments" to attachments,
            "aiPriority" to aiPriority,
            "aiClassification" to aiClassification,
            "aiAction" to aiAction
        )

        db.collection("reports")
            .add(report)
            .addOnSuccessListener {
                val submitButton = findViewById<MaterialButton>(R.id.submit_button)
                submitButton.isEnabled = true
                submitButton.text = "Submit Report"
                showSuccessDialog()
            }
            .addOnFailureListener { exception ->
                val submitButton = findViewById<MaterialButton>(R.id.submit_button)
                submitButton.isEnabled = true
                submitButton.text = "Submit Report"
                Toast.makeText(this, "Failed to submit report: ${exception.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showSuccessDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_report_submitted, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.view.View>(R.id.btn_track_report).setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, TrackReportsActivity::class.java))
            finish()
        }

        dialogView.findViewById<android.view.View>(R.id.btn_back_home).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun selectCategory(index: Int) {
        selectedCategoryIndex = index
        categories.forEachIndexed { i, layout ->
            if (i == index) {
                layout.background = ContextCompat.getDrawable(this, R.drawable.recent_activity_background)
            } else {
                layout.background = ContextCompat.getDrawable(this, R.drawable.category_card_background)
            }
        }
    }
}
