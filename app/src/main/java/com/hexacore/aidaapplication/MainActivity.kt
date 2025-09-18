package com.hexacore.aidaapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.*
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import java.io.File
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.view.Gravity
import android.widget.ImageButton
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import android.media.SoundPool;
import android.os.Environment
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.EditText
import java.util.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import android.widget.FrameLayout
import android.widget.TableLayout
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat

import android.speech.tts.UtteranceProgressListener
import okhttp3.RequestBody.Companion.toRequestBody

import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class CalendarData(
    val year: Int,
    val months: List<MonthData>
)

data class MonthData(
    val name: String,
    val days: List<DayData>
)

data class DayData(
    val day: Int,
    val events: MutableList<EventData>
)

data class EventData(
    val title: String,
    val description: String? = null
)

data class CalendarEvent(val day: Int, val title: String)

class MainActivity : ComponentActivity() {
    private lateinit var soundPool: SoundPool
    private var aida_awake_sound: Int = 0
    private var aida_asleep_sound: Int = 0
    private var porcupineManager: PorcupineManager? = null

    private lateinit var mediaRecorder: MediaRecorder
    private var dirPath = ""
    private var filename = ""
    private val RECORD_AUDIO_REQUEST_CODE = 1

    // UI elements, initialized later
    private lateinit var textDisplay: TextView
    private lateinit var aiImage: ImageView
    private lateinit var commandHint: TextView
    private lateinit var statusTextView: TextView
    private lateinit var aiStatusButton: ImageView
    private lateinit var scrollContainer: ScrollView

    private var recordedAudio: ByteArray? = null

    private lateinit var noteAppLayout: LinearLayout
    private lateinit var noteButton: ImageButton
    private lateinit var currentAppDisplay: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var calendarButton: ImageButton
    private lateinit var closeAppButton: Button
    private lateinit var addNoteButton: Button
    private lateinit var container: FrameLayout
    private lateinit var meetingText: TextView

    // Track the selected day globally in the activity
    private var selectedDay: Int? = null

    private val notesFile: File by lazy {
        File(filesDir, "notes.json")
    }

    private var isNoteAppOpen = false
    private var isCalendarOpen = false

    private val eventsFileName = "calendar_2025.json"

    // JSON file (calendar.json stored in assets or internal storage)
    private val calendarFile: File by lazy {
        File(filesDir, "calendar.json")
    }

    private var isAlarmOpen = false
    private var isMeetingModeOpen = false

    private lateinit var calendarJson: JSONObject
    private lateinit var alarmButton: ImageButton
    private lateinit var meetingButton: ImageButton
    private lateinit var alarmContent: LinearLayout
    private lateinit var meetingModeDisplay: LinearLayout
    private var currentYear = 2025
    private var currentMonthIndex = 0  // 0 = January

    private var REQUEST_CODE_SPEECH = 100
    private var openGoogleSTT = false
    private var isConnectedToServer = false

    lateinit var tflite: Interpreter
    lateinit var words: List<String>
    lateinit var classes: List<String>

    private var isSpeaking = false
    private var aidaResponses = arrayOf("What can i do for you today?", "How can i help you?", "What can i do for you?", "How can i assist you?", "What can i do today?", "How can i help you today?", "What can i do for today?", "How can i assist you today?", "What can i do today?")

    // 📅 Show today's month and highlight today's day
    val today = LocalDate.now()
    val currentDay = today.dayOfMonth

    var isRecordingMeeting = false

    private lateinit var api: ApiService

    private val keywordCallback = PorcupineManagerCallback { keywordIndex ->
        when (keywordIndex) {
            0 -> {
                runOnUiThread {
                    aiImage.setImageResource(R.drawable.ai_awake)
                    commandHint.text = "Aida is awake... say a command!"
                    statusTextView.text = "Listening..."
                    aiStatusButton.setImageResource(R.drawable.ai_awake)
                }
                println("Porcupine detected!")

                openGoogleSTT = true

                soundPool.play(aida_awake_sound, 0.6f, 0.6f, 0, 0, 1f)

                speakAndThenListen(aidaResponses.random())

                aiStatusButton.setImageResource(R.drawable.ai_processing)
                commandHint.text = "Say... Hey Aida!"
                statusTextView.text = "Sleeping..."

                // Start recording
                //startRecording()
            }
            1 -> println("Bumblebee detected!")
        }
    }

    fun loadJsonArray(context: Context, fileName: String): List<String> {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        return org.json.JSONArray(jsonString).let { array ->
            List(array.length()) { i -> array.getString(i) }
        }
    }

    fun initializeModel(context: Context) {
        // Load model
        val model = context.assets.open("intent_detection_model.tflite").use { input ->
            input.readBytes()
        }
        val assetFileDescriptor = context.assets.openFd("intent_detection_model.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        tflite = Interpreter(modelBuffer)

        // Load words and classes
        words = loadJsonArray(context, "words.json")
        classes = loadJsonArray(context, "classes.json")
    }

    private fun speakAndThenListen(text: String) {
        if (::tts.isInitialized) {
            tts.setPitch(1.0f)      // Normal pitch
            tts.setSpeechRate(1.0f) // Normal speed

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    runOnUiThread {
                        startSpeechToText() // 👈 Start listening only after speech ends
                    }
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                }
            })

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
        }
    }

    private fun startSpeechToText() {
        if(openGoogleSTT) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something…")
            }
            try {
                startActivityForResult(intent, REQUEST_CODE_SPEECH)
            } catch (e: Exception) {
                Toast.makeText(this, "Speech recognition not supported", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val spokenText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            spokenText?.let {
                val (intent, confidence) = predictIntent(it)

                outputActionBasedOnIntent(intent, it)

                openGoogleSTT = false
                //Toast.makeText(this, "You said: $it\nPrediction: $intent (confidence: ${"%.2f".format(confidence)})", Toast.LENGTH_LONG).show()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun predictIntent(sentence: String): Pair<String, Float> {
        val input = preprocessInput(sentence)
        val inputArray = arrayOf(input)
        val output = Array(1) { FloatArray(classes.size) }

        tflite.run(inputArray, output)

        val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        val confidence = output[0][maxIndex]

        return classes[maxIndex] to confidence
    }

    fun preprocessInput(sentence: String): FloatArray {
        val tokens = sentence.lowercase(Locale.getDefault())
            .replace("[!?.,]".toRegex(), "") // remove punctuation
            .split(" ")

        val bag = FloatArray(words.size) { 0f }
        for ((i, word) in words.withIndex()) {
            if (tokens.contains(word)) {
                bag[i] = 1f
            }
        }
        return bag
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        askForServerIp { ip ->
            setApi(ip)
            retryConnection()
        }

        // ✅ Clear transcript file on app start
        val transcriptFile = File(filesDir, "transcripted_meeting.txt")
        if (transcriptFile.exists()) {
            FileOutputStream(transcriptFile).use { fos ->
                fos.write(byteArrayOf()) // clears content but keeps file
            }
        } else {
            transcriptFile.createNewFile() // make sure it exists
        }


        // Initialize UI elements AFTER setContentView
        textDisplay = findViewById(R.id.text_display)
        aiImage = findViewById(R.id.aiImage)
        commandHint = findViewById(R.id.command_hint)
        statusTextView = findViewById(R.id.status_textview)
        aiStatusButton = findViewById(R.id.aiStatusButton)
        currentAppDisplay = findViewById(R.id.current_app_display)
        noteAppLayout = findViewById(R.id.note_app_layout)
        noteButton = findViewById(R.id.noteButton)
        scrollContainer = findViewById(R.id.scroll_container)
        addNoteButton = findViewById(R.id.addNoteButton)
        calendarButton = findViewById(R.id.calendarButton)
        closeAppButton = findViewById(R.id.exit_app_button)
        alarmButton = findViewById(R.id.alarmButton)
        meetingButton = findViewById(R.id.meetingButton)
        alarmContent = findViewById(R.id.alarm_content)
        meetingModeDisplay = findViewById(R.id.meetingModeDisplay)
        meetingText = findViewById(R.id.meeting_text)

        val playBtn = findViewById<ImageButton>(R.id.playButton)
        val stopBtn = findViewById<ImageButton>(R.id.stopButton)

        currentYear = today.year
        currentMonthIndex = today.monthValue - 1

        container = findViewById<FrameLayout>(R.id.calendarContainer)

        // 🎵 SoundPool (modern builder API)
        soundPool = SoundPool.Builder()
            .setMaxStreams(2) // allow playing awake + asleep
            .build()

        aida_awake_sound = soundPool.load(this, R.raw.aida_sound_awake, 1)
        aida_asleep_sound = soundPool.load(this, R.raw.aida_sound_sleep, 1)

        // 🗣️ Text-to-Speech setup
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    println("❌ TTS language not supported.")
                } else {
                    // ✅ Attach listener here
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                        }

                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                        }

                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                        }
                    })
                }
            } else {
                println("❌ TTS initialization failed.")
            }
        }

        // 🖼️ Hide both status bar & navigation bar for full screen
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 🎤 Request RECORD_AUDIO permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
        } else {
            initPorcupine()
        }

        // 📝 Notes Button
        noteButton.setOnClickListener {
            if (isNoteAppOpen) {
                // Close Notes
                closeAllApps()

            } else {
                // Open Notes → close Calendar if open
                closeAllApps()
                isNoteAppOpen = true
                isCalendarOpen = false
                container.visibility = View.GONE

                scrollContainer.visibility = View.VISIBLE
                noteAppLayout.visibility = View.VISIBLE
                currentAppDisplay.visibility = View.VISIBLE
                addNoteButton.visibility = View.VISIBLE

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                textDisplay.visibility = View.GONE
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.text = "Note App"

                closeAppButton.visibility = View.VISIBLE
                addNoteButton.visibility = View.VISIBLE
            }
        }

        // ➕ Add Note
        addNoteButton.setOnClickListener {
            val newNoteId = View.generateViewId()
            val newNoteTitle = "Untitled"
            val newNoteContent = ""

            saveNoteToJson(newNoteTitle, newNoteContent)

            val newNoteLayout = createNoteLayout(newNoteId, newNoteTitle, newNoteContent)
            noteAppLayout.addView(newNoteLayout)

            scrollContainer.post {
                scrollContainer.fullScroll(View.FOCUS_RIGHT)
            }
        }

        // 📅 Calendar Button
        calendarButton.setOnClickListener {
            if (isCalendarOpen) {
                closeAllApps()
            } else {
                println("📂 Opening calendar...")
                closeAllApps()

                // Open Calendar → close Notes if open
                isCalendarOpen = true
                isNoteAppOpen = false
                scrollContainer.visibility = View.GONE
                noteAppLayout.visibility = View.GONE
                addNoteButton.visibility = View.GONE

                container.visibility = View.VISIBLE

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                textDisplay.visibility = View.GONE
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.visibility = View.VISIBLE
                currentAppDisplay.text = "Calendar App"
                closeAppButton.visibility = View.VISIBLE

                // 🔹 Reset to today's date
                val today = LocalDate.now()
                currentYear = today.year
                currentMonthIndex = today.monthValue - 1
                selectedDay = today.dayOfMonth

                // 🔹 Load saved calendar JSON
                loadCalendarDataFromFile()  // loads calendar_2025.json into memory

                // 🔹 Show calendar with today's highlighted day
                showMonthCalendar(container, currentYear, currentMonthIndex + 1, selectedDay)
            }
        }

        closeAppButton.setOnClickListener {
            closeAllApps()
        }


        aiStatusButton.setOnClickListener {
            val helpMessage = """
            🤖 What AIDA Can Do:
            
            📓 Notes
            • Manually add and edit notes.
            • Voice: "Create a new note with a title New Note and the content is Agenda for today is to order food."
            • Voice: "Delete a note with a title <note title>."
    
            📅 Calendar
            • Manually view and navigate months.
            • Voice: "Create a calendar event on <month> <day> <year> about Company outing."
    
            📝 Meetings
            • Voice: "Start live meeting" → begins transcription.
            • Voice: "Summarize the meeting for me" → generates a summary.
    
            🌐 Search
            • Voice: "Search what/when/where/who/how is ..." → performs an online search.
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Help with AIDA")
                .setMessage(helpMessage)
                .setPositiveButton("Got it") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        alarmButton.setOnClickListener {
            if (isAlarmOpen) {
                isAlarmOpen = false
                closeAllApps()
            } else {
                // ✅ Open Alarm → close other apps if open
                closeAllApps()

                isAlarmOpen = true

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE

                // Show Alarm
                alarmContent.visibility = View.VISIBLE

                // Update current app display
                currentAppDisplay.visibility = View.VISIBLE
                currentAppDisplay.text = "Alarm App"
                closeAppButton.visibility = View.VISIBLE
            }
        }

        meetingButton.setOnClickListener {
            if (isMeetingModeOpen) {
                isMeetingModeOpen = false
                closeAllApps()
            } else {
                // ✅ Open Meeting Mode → close other apps if open
                closeAllApps()

                isMeetingModeOpen = true

                // Show Meeting Mode
                meetingModeDisplay.visibility = View.VISIBLE

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE

                // Update current app display
                currentAppDisplay.visibility = View.VISIBLE
                currentAppDisplay.text = "Meeting Mode"
                closeAppButton.visibility = View.VISIBLE
            }
        }

        playBtn.setOnClickListener {
            if(isConnectedToServer) {
                playBtn.visibility = View.GONE
                stopBtn.visibility = View.VISIBLE
                //TODO: Start the record for 5 minutes and send it to the server everytime, each time
                //looping until the stop button is clicked

                isRecordingMeeting = true

                //Clearing the meeting text display before recording
                meetingText.text = ""

                println("Recording Started")

                startLiveMeetingRecording()
            } else {
                showConnectionErrorDialog()
            }
        }

        stopBtn.setOnClickListener {
            stopBtn.visibility = View.GONE
            playBtn.visibility = View.VISIBLE

            isRecordingMeeting = false

            println("Recording stopped")
            // 👉 Stop recording logic here
        }


        setupAlarmUI(this)

        // Load Notes + Calendar
        initializeModel(this)
        ensureCalendarFile()
        loadNotesFromJson()
        loadCalendarJson()

        showMonthCalendar(container, currentYear, currentMonthIndex + 1, today.dayOfMonth)
        println("🔄 Initial calendar render: $currentYear-${currentMonthIndex + 1}-${today.dayOfMonth}")
    }

    // put these functions outside onCreate in MainActivity
    private fun showConnectionErrorDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle("Connection Error")
            .setMessage(
                """
            Connection to the server failed. Please check the following:
            
            1) Check the IP address on the Retrofit client and the network
               security config if it matches with the Flask server
               
            2) Check if the server is online
            
            3) Try again later
            """.trimIndent()
            )
            .setPositiveButton("Got it") { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton("Try Again") { dialog, _ ->
                dialog.dismiss()
                retryConnection()
            }
            .show()
    }

    private fun retryConnection() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = api.testConnection() // ✅ use dynamic API
                withContext(Dispatchers.Main) {
                    if (response.response == "Code 200") {
                        isConnectedToServer = true
                        Toast.makeText(
                            this@MainActivity,
                            "Server connected!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showConnectionErrorDialog()
                }
            }
        }
    }

    fun setupAlarmUI(activity: Activity) {
        val alarmTime = activity.findViewById<TextView>(R.id.alarm_time)
        val startAlarmBtn = activity.findViewById<Button>(R.id.start_alarm)
        val cancelAlarmBtn = activity.findViewById<Button>(R.id.cancel_alarm)

        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(activity, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        var calendar: Calendar = Calendar.getInstance()

        // Click on time to pick new time
        alarmTime.setOnClickListener {
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            TimePickerDialog(
                activity,
                { _, selectedHour, selectedMinute ->
                    calendar.set(Calendar.HOUR_OF_DAY, selectedHour)
                    calendar.set(Calendar.MINUTE, selectedMinute)
                    calendar.set(Calendar.SECOND, 0)

                    val amPm = if (selectedHour < 12) "AM" else "PM"
                    val displayHour = if (selectedHour % 12 == 0) 12 else selectedHour % 12
                    alarmTime.text =
                        String.format("%02d:%02d %s", displayHour, selectedMinute, amPm)
                },
                hour, minute, false
            ).show()
        }

        // Start Alarm
        startAlarmBtn.setOnClickListener {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
            Toast.makeText(activity, "⏰ Alarm set!", Toast.LENGTH_SHORT).show()
        }

        // Cancel Alarm
        cancelAlarmBtn.setOnClickListener {
            alarmManager.cancel(pendingIntent)
            Toast.makeText(activity, "❌ Alarm cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    fun createAlarmDirectly(activity: Activity, hour: Int, minute: Int, isAm: Boolean) {
        val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(activity, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance()

        // Convert AM/PM to 24-hour format
        var hour24 = hour % 12
        if (!isAm) {
            hour24 += 12
        }

        calendar.set(Calendar.HOUR_OF_DAY, hour24)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // If the chosen time is already passed today, schedule it for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Set the alarm
        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        val amPm = if (isAm) "AM" else "PM"
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        Toast.makeText(
            activity,
            "⏰ Alarm set for %02d:%02d %s".format(displayHour, minute, amPm),
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun loadCalendarDataFromFile(): CalendarData {
        val file = File(filesDir, eventsFileName)
        if (!file.exists()) {
            // Copy from assets if file doesn't exist
            assets.open(eventsFileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        val json = file.readText()
        return Gson().fromJson(json, CalendarData::class.java)
    }

    private fun closeAllApps() {
        // Reset all flags
        isNoteAppOpen = false
        isCalendarOpen = false
        isAlarmOpen = false
        isMeetingModeOpen = false

        // Hide all layouts
        scrollContainer.visibility = View.GONE
        noteAppLayout.visibility = View.GONE
        addNoteButton.visibility = View.GONE

        container.visibility = View.GONE
        alarmContent.visibility = View.GONE
        meetingModeDisplay.visibility = View.GONE

        // Reset display
        currentAppDisplay.visibility = View.GONE
        closeAppButton.visibility = View.GONE

        aiImage.visibility = View.VISIBLE
        commandHint.visibility = View.VISIBLE
        textDisplay.visibility = View.GONE
        aiStatusButton.setImageResource(R.drawable.ai_processing)
        statusTextView.text = "Sleeping..."
    }


    private fun loadCalendarJson() {
        val jsonString = assets.open("calendar_2025.json").bufferedReader().use { it.readText() }
        calendarJson = JSONObject(jsonString)
    }

    private fun showMonthCalendar(
        container: FrameLayout,
        year: Int,
        month: Int,
        highlightDay: Int? = null
    ) {
        println("📅 showMonthCalendar() called with year=$year, month=$month, highlightDay=$highlightDay")

        container.removeAllViews()
        val calendarView = layoutInflater.inflate(R.layout.calendar_event, container, false)

        val monthTitle = calendarView.findViewById<TextView>(R.id.monthTitle)
        val monthChooserButton = calendarView.findViewById<Button>(R.id.monthChooserButton)

        val monthName = LocalDate.of(year, month, 1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        monthTitle.text = "$monthName $year"
        println("✅ Month title set: $monthName $year")

        val firstDay = LocalDate.of(year, month, 1)
        val startDayOfWeek = firstDay.dayOfWeek.value % 7
        val daysInMonth = firstDay.lengthOfMonth()
        println("ℹ️ Month $monthName has $daysInMonth days, starts on weekday index=$startDayOfWeek")

        val today = LocalDate.now()
        // 🔹 Reload calendar JSON every time
        val calendarData = loadCalendarData()
        val monthData = calendarData.months[month - 1]  // get only the currently selected month

        // 🟨 Setup day cells
        for (i in 0 until 42) {
            val cellId = resources.getIdentifier("cell_$i", "id", packageName)
            val cell = calendarView.findViewById<TextView>(cellId)
            val dayNumber = i - startDayOfWeek + 1

            if (dayNumber in 1..daysInMonth) {
                cell.text = dayNumber.toString()
                val dayData = monthData.days.firstOrNull { it.day == dayNumber }
                val hasEvents = dayData?.events?.isNotEmpty() == true

                // Highlight conditions
                val isToday = year == today.year && month == today.monthValue && dayNumber == today.dayOfMonth
                val isHighlighted = highlightDay != null && dayNumber == highlightDay

                when {
                    isHighlighted -> {
                        cell.setBackgroundColor(Color.parseColor("#FFCC00")) // yellow for selected day
                        cell.setTextColor(Color.BLACK)
                        println("⭐ Highlighted selected day: $dayNumber")
                    }
                    isToday -> {
                        cell.setBackgroundColor(Color.parseColor("#FFE066")) // lighter yellow for today
                        cell.setTextColor(Color.BLACK)
                        println("⭐ Highlighted today: $dayNumber")
                    }
                    hasEvents -> {
                        cell.setBackgroundColor(Color.parseColor("#4CAF50")) // green for event days
                        cell.setTextColor(Color.WHITE)
                        println("📌 Highlighted event day: $dayNumber")
                    }
                    else -> {
                        cell.setBackgroundColor(Color.WHITE)
                        cell.setTextColor(Color.BLACK)
                    }
                }

                // 👉 Click to add event
                cell.setOnClickListener {
                    println("✅ Day clicked: $dayNumber/$month/$year")

                    val dayEvents = dayData?.events ?: mutableListOf()

                    if (dayEvents.isNotEmpty()) {
                        // Day has existing events → show them in a list with delete option
                        val eventTitles = dayEvents.map { it.title }.toTypedArray()

                        AlertDialog.Builder(this)
                            .setTitle("Events on $dayNumber ${monthData.name}")
                            .setItems(eventTitles) { _, index ->
                                // When an event is clicked → show options: Delete or Cancel
                                val selectedEvent = dayEvents[index]

                                AlertDialog.Builder(this)
                                    .setTitle(selectedEvent.title)
                                    .setMessage("Do you want to delete this event?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        dayEvents.removeAt(index)
                                        saveCalendarData(calendarData)
                                        Toast.makeText(
                                            this,
                                            "🗑️ Event deleted",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showMonthCalendar(container, year, month, highlightDay = dayNumber)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            .setPositiveButton("Add New Event") { _, _ ->
                                // Add a new event
                                val input = EditText(this)
                                AlertDialog.Builder(this)
                                    .setTitle("Add Event")
                                    .setView(input)
                                    .setPositiveButton("Save") { _, _ ->
                                        val eventTitle = input.text.toString().ifBlank { "Untitled Event" }
                                        dayEvents.add(EventData(eventTitle))
                                        saveCalendarData(calendarData)
                                        Toast.makeText(
                                            this,
                                            "📌 Event added on $dayNumber ${monthData.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showMonthCalendar(container, year, month, highlightDay = dayNumber)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    } else {
                        // No events → add directly
                        val input = EditText(this)
                        AlertDialog.Builder(this)
                            .setTitle("Add Event")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val eventTitle = input.text.toString().ifBlank { "Untitled Event" }
                                dayEvents.add(EventData(eventTitle))
                                saveCalendarData(calendarData)
                                Toast.makeText(
                                    this,
                                    "📌 Event added on $dayNumber ${monthData.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showMonthCalendar(container, year, month, highlightDay = dayNumber)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }



            } else {
                // Empty cells
                cell.text = ""
                cell.setBackgroundColor(Color.LTGRAY)
                cell.setOnClickListener(null)
            }
        }

        // 🟢 Month chooser button
        monthChooserButton.setOnClickListener {
            val months = arrayOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            )

            if (monthChooserButton.text == "Choose Month") {
                println("📌 Choose Month clicked")

                AlertDialog.Builder(this)
                    .setTitle("Pick a Month")
                    .setItems(months) { _, which ->
                        currentMonthIndex = which
                        println("👉 User picked month index=$which (${months[which]})")

                        val today = LocalDate.now()
                        val highlightDay = if (which == today.monthValue - 1 && year == today.year) {
                            today.dayOfMonth
                        } else {
                            null
                        }

                        // 🔹 Always reload JSON for selected month
                        showMonthCalendar(container, year, which + 1, highlightDay)

                        monthChooserButton.text = "Back"
                    }
                    .show()
            } else {
                println("🔙 Back clicked → resetting to today")
                val today = LocalDate.now()
                currentYear = today.year
                currentMonthIndex = today.monthValue - 1
                val todayDay = today.dayOfMonth

                // 🔹 Reload JSON for current month
                showMonthCalendar(container, currentYear, currentMonthIndex + 1, todayDay)
                monthChooserButton.text = "Choose Month"
            }
        }

        container.addView(calendarView)
        println("✅ Calendar UI updated for $monthName $year")
    }

    private fun ensureCalendarFile() {
        val file = File(filesDir, "calendar_2025.json")
        if (!file.exists()) {
            assets.open("calendar_2025.json").use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            println("📂 Copied calendar_2025.json from assets to filesDir")
        }
    }

    private fun loadCalendarData(): CalendarData {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "calendar_2025.json")

        // If file doesn't exist, copy a default from assets
        if (!file.exists()) {
            assets.open("calendar_2025.json").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("📄 Copied default calendar_2025.json to Documents folder")
        }

        val json = file.readText()
        return Gson().fromJson(json, CalendarData::class.java)
    }


    private fun saveCalendarData(calendarData: CalendarData) {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "calendar_2025.json")
        file.writeText(Gson().toJson(calendarData))
        println("💾 Calendar saved to ${file.absolutePath}")
    }


    fun showEventFromYearMonthDay(year: String, month: String, day: Int) {
        if (!::calendarJson.isInitialized) loadCalendarJson()

        val months = calendarJson.getJSONArray("months")
        var targetMonthIndex = -1

        // Find the month in JSON
        for (i in 0 until months.length()) {
            val m = months.getJSONObject(i)
            if (m.getString("name").equals(month, ignoreCase = true)) {
                targetMonthIndex = i
                break
            }
        }

        if (targetMonthIndex == -1) {
            println("❌ Month $month not found in JSON")
            return
        }

        currentMonthIndex = targetMonthIndex
        val container = findViewById<FrameLayout>(R.id.calendarContainer)
        showMonthCalendar(container, year.toInt(), currentMonthIndex + 1, day)
    }

    private fun speakText(text: String) {
        if (::tts.isInitialized) {
            tts.setPitch(1.0f)      // Normal pitch
            tts.setSpeechRate(1.0f) // Normal speed
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
        }
    }

    fun deleteNoteByTitle(context: Context, fileName: String, titleToDelete: String) {
        try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) return

            val rootObject = JSONObject(file.readText())        // parse as object
            val notesJson = rootObject.getJSONArray("notes")    // get the array

            val updatedNotes = JSONArray()

            for (i in 0 until notesJson.length()) {
                val note = notesJson.getJSONObject(i)
                val noteTitle = note.getString("title")

                // Compare ignoring case + trim spaces
                if (!noteTitle.equals(titleToDelete.trim(), ignoreCase = true)) {
                    updatedNotes.put(note)
                }
            }

            // Save back into same object structure
            rootObject.put("notes", updatedNotes)
            file.writeText(rootObject.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun saveNoteToJson(title: String, content: String) {
        val jsonObject = if (notesFile.exists()) {
            try {
                JSONObject(notesFile.readText())
            } catch (e: Exception) {
                JSONObject().apply { put("notes", JSONArray()) }
            }
        } else {
            JSONObject().apply { put("notes", JSONArray()) }
        }

        val notesArray = jsonObject.getJSONArray("notes")

        val noteObject = JSONObject().apply {
            put("title", title)
            put("content", content)
        }
        notesArray.put(noteObject)

        jsonObject.put("notes", notesArray)

        notesFile.writeText(jsonObject.toString())
    }

    private fun loadNotesFromJson() {
        noteAppLayout?.removeAllViews()

        if (notesFile.exists()) {
            try {
                val jsonObject = JSONObject(notesFile.readText())
                val notesArray = jsonObject.getJSONArray("notes")

                for (i in 0 until notesArray.length()) {
                    val noteObject = notesArray.getJSONObject(i)
                    val title = noteObject.optString("title", "Untitled")
                    val content = noteObject.optString("content", "")
                    noteAppLayout?.addView(createNoteLayout(View.generateViewId(), title, content))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // function to create a note layout
    fun createNoteLayout(noteId: Int, title: String, content: String): LinearLayout {
        val noteLayout = LinearLayout(this).apply {
            id = noteId
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#8f8f8f"))
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(200),
                dpToPx(400)
            ).apply { rightMargin = dpToPx(30) }
        }

        val actionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#ffbf00"))
            setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(60)
            )
        }

        // 🔹 Editable title
        val noteName = EditText(this).apply {
            setText(title)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 🔹 Editable content
        val inputText = EditText(this).apply {
            setText(content)
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = false
            gravity = Gravity.TOP
            setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 🔹 Save note when user hides keyboard / loses focus
        noteName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveNoteToJson(
                    noteName.text.toString(),
                    inputText.text.toString()
                )
            }
        }

        inputText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveNoteToJson(
                    noteName.text.toString(),
                    inputText.text.toString()
                )
            }
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.close_icon)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = Gravity.END
            }

            setOnClickListener {
                deleteNoteByTitle(this@MainActivity, "notes.json", noteName.text.toString())
                (noteLayout.parent as? LinearLayout)?.removeView(noteLayout)
            }
        }

        val scrollView = ScrollView(this).apply {
            id = View.generateViewId()
            setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(inputText)
        }

        actionLayout.addView(noteName)
        actionLayout.addView(closeButton)
        noteLayout.addView(actionLayout)
        noteLayout.addView(scrollView)

        return noteLayout
    }


    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    fun aiCreateNewNote(title: String, content: String) {
        saveNoteToJson(title, content)
        loadNotesFromJson()
    }


    private fun initPorcupine() {
        // Copy the keyword file from assets to internal storage
        val keywordFile = File(filesDir, "Hey-Aida_en_android_v3_0_0.ppn")
        if (!keywordFile.exists()) {
            assets.open("Hey-Aida_en_android_v3_0_0.ppn").use { input ->
                keywordFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        try {
            // Use the builder with the custom keyword file
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey("VM996Z/2j8ghpUlIqlqxMmVAOxOHHCMujdWtGLAZ3i43Q0vTinykmg==")
                .setKeywordPath(keywordFile.absolutePath) // <-- absolute path in internal storage
                .setSensitivity(0.5f)
                .build(this, keywordCallback)

            porcupineManager?.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // Add this method to stop the MediaRecorder
    private fun stopRecording() {
        try {
            mediaRecorder.stop()
            mediaRecorder.release()
            println("Recording stopped: $filename.wav")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initPorcupine()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        Thread {
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // ✅ better noise handling
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val recordedData = mutableListOf<Byte>()
                val buffer = ByteArray(bufferSize)
                val maxDurationMillis = 15000L // safety cap (15s)
                val startTime = System.currentTimeMillis()

                // --- Step 1: Measure background noise before recording ---
                var noiseSamples = 0
                var noiseSum = 0.0
                audioRecord.startRecording()
                val noiseCheckTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - noiseCheckTime < 1000) { // 1 second calibration
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val shorts = ShortArray(read / 2)
                        ByteBuffer.wrap(buffer, 0, read)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shorts)

                        var sum = 0.0
                        for (s in shorts) sum += (s * s).toDouble()
                        val rms = Math.sqrt(sum / shorts.size)
                        noiseSum += rms
                        noiseSamples++
                    }
                }

                val backgroundRms = if (noiseSamples > 0) noiseSum / noiseSamples else 400.0
                val silenceThreshold = backgroundRms * 0.8   // ✅ adaptive threshold, more tolerant
                val silenceDuration = 4000L                  // ✅ 4s silence before stopping
                val smoothingWindow = 5                      // average over 5 buffers
                val rmsHistory = ArrayDeque<Double>()

                var lastNonSilentTime = System.currentTimeMillis()
                println("Adaptive silence threshold: $silenceThreshold (background RMS: $backgroundRms)")

                // --- Step 2: Actual recording loop ---
                while (System.currentTimeMillis() - startTime < maxDurationMillis) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recordedData.addAll(buffer.take(read))

                        // Calculate RMS
                        var sum = 0.0
                        val shorts = ShortArray(read / 2)
                        ByteBuffer.wrap(buffer, 0, read)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shorts)

                        for (s in shorts) sum += (s * s).toDouble()
                        val rms = Math.sqrt(sum / shorts.size)

                        // Smooth RMS with rolling average
                        rmsHistory.addLast(rms)
                        if (rmsHistory.size > smoothingWindow) rmsHistory.removeFirst()
                        val avgRms = rmsHistory.average()

                        // Silence detection with smoothing + tolerance
                        if (avgRms > silenceThreshold) {
                            lastNonSilentTime = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastNonSilentTime > silenceDuration) {
                            println("Detected ~${silenceDuration / 1000}s of silence, stopping...")
                            break
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()

                // Notify user recording done
                soundPool.play(aida_asleep_sound, 1f, 1f, 0, 0, 1f)

                val pcmBytes = recordedData.toByteArray()

                // Save as temporary WAV
                val wavFile = File.createTempFile("temp_audio", ".wav", cacheDir)
                FileOutputStream(wavFile).use { fos ->
                    fos.write(pcmToWav(pcmBytes, sampleRate, 1, 16))
                }

                println("Recording completed, WAV file saved at: ${wavFile.absolutePath}")

                // Send WAV to server asynchronously
                sendWavToServer(wavFile)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }



    /** Helper: Wrap PCM bytes in WAV header */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = 36 + pcmData.size
        val header = ByteArray(44)

        // ChunkID "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // ChunkSize
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen)

        // Format "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // Subchunk1ID "fmt "
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1Size 16 for PCM
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16)

        // AudioFormat 1 = PCM
        ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(1)

        // NumChannels
        ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort())

        // SampleRate
        ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate)

        // ByteRate
        ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate)

        // BlockAlign
        ByteBuffer.wrap(header, 32, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((channels * bitsPerSample / 8).toShort())

        // BitsPerSample
        ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitsPerSample.toShort())

        // Subchunk2ID "data"
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Subchunk2Size
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcmData.size)

        return header + pcmData
    }

    fun sendWavToServer(wavFile: File) {
        println("Sending WAV file to server: ${wavFile.absolutePath}, size=${wavFile.length()} bytes")

        CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            val maxRetries = 1
            var success = false

            while (attempt <= maxRetries && !success) {
                try {
                    attempt++
                    println("Attempt $attempt to send file...")

                    val requestFile = wavFile.asRequestBody("audio/wav".toMediaType())
                    val body = MultipartBody.Part.createFormData("file", wavFile.name, requestFile)

                    val response: ResponseData = api.sendWavFile(body)

                    val transcript = response.transcript
                    val responseText = response.response
                    val key = response.key

                    withContext(Dispatchers.Main) {
                        println("✅ Server response received (attempt $attempt): $transcript")
                        when (key) {
                            "search-results" -> {
                                if(isConnectedToServer) {
                                    aiStatusButton.setImageResource(R.drawable.ai_processing)
                                    statusTextView.text = "Sleeping..."

                                    scrollContainer.visibility = View.VISIBLE

                                    textDisplay.visibility = View.VISIBLE
                                    textDisplay.text = "Loading result..."
                                    aiImage.visibility = View.GONE
                                    commandHint.visibility = View.GONE
                                    noteAppLayout?.visibility = View.GONE

                                    textDisplay.text = responseText
                                    speakText(responseText)
                                } else {
                                    showConnectionErrorDialog()
                                }
                            }
                            "close-search" -> {
                                aiStatusButton.setImageResource(R.drawable.ai_processing)
                                aiImage.setImageResource(R.drawable.ai_processing)
                                statusTextView.text = "Sleeping..."
                                commandHint.text = "Say... Hey Aida!"

                                scrollContainer.visibility = View.GONE

                                textDisplay.visibility = View.GONE
                                aiImage.visibility = View.VISIBLE
                                commandHint.visibility = View.VISIBLE
                                noteAppLayout?.visibility = View.GONE
                                currentAppDisplay.visibility = View.GONE

                                addNoteButton.visibility = View.GONE
                            }

                            "start-live-meeting" -> {
                                aiStatusButton.setImageResource(R.drawable.ai_processing)
                                statusTextView.text = "Sleeping..."

                                closeAllApps()

                                aiImage.visibility = View.GONE
                                commandHint.visibility = View.GONE

                                currentAppDisplay.text = "Meeting Mode"
                                meetingModeDisplay.visibility = View.VISIBLE

                                isRecordingMeeting = true

                                print("Recording started")

                                val playBtn = findViewById<ImageButton>(R.id.playButton)
                                val stopBtn = findViewById<ImageButton>(R.id.stopButton)

                                playBtn.visibility = View.GONE
                                stopBtn.visibility = View.VISIBLE
                                startLiveMeetingRecording()
                            }

                            "stop-live-meeting" -> {
                                aiStatusButton.setImageResource(R.drawable.ai_processing)
                                statusTextView.text = "Sleeping..."

                                val playBtn = findViewById<ImageButton>(R.id.playButton)
                                val stopBtn = findViewById<ImageButton>(R.id.stopButton)

                                playBtn.visibility = View.VISIBLE
                                stopBtn.visibility = View.GONE

                                isRecordingMeeting = false
                            }

                            "summarize-meeting" -> {
                                aiStatusButton.setImageResource(R.drawable.ai_processing)
                                statusTextView.text = "Sleeping..."

                                scrollContainer.visibility = View.VISIBLE

                                textDisplay.visibility = View.VISIBLE
                                textDisplay.text = "Loading result..."
                                aiImage.visibility = View.GONE
                                commandHint.visibility = View.GONE
                                noteAppLayout.visibility = View.GONE

                                textDisplay.text = responseText

                                sendMeetingFileToServer()
                            }

                            "transcribed-meeting" -> {
                                print("Other Transcripts {$responseText}")

                            }

                        }
                    }

                    success = true

                } catch (e: Exception) {
                    e.printStackTrace()
                    if (attempt > maxRetries) {
                        withContext(Dispatchers.Main) {
                            textDisplay.text = "⚠️ Failed to connect to server. Please try again."
                            statusTextView.text = "Error"
                            aiStatusButton.setImageResource(R.drawable.ai_processing)
                        }
                    } else {
                        println("Retrying... (attempt $attempt failed)")
                    }
                }
            }
        }
    }


    private fun startLiveMeetingRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val recordedData = mutableListOf<Byte>()
                val buffer = ByteArray(bufferSize)

                audioRecord.startRecording()
                val startTime = System.currentTimeMillis()
                val maxDurationMillis = 60000L

                // Keep recording while isRecordingMeeting is true
                while (isRecordingMeeting && System.currentTimeMillis() - startTime < maxDurationMillis) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recordedData.addAll(buffer.take(read))
                    }
                }

                audioRecord.stop()
                audioRecord.release()

                if (recordedData.isNotEmpty()) {
                    val pcmBytes = recordedData.toByteArray()
                    val wavFile = File.createTempFile("meeting_audio", ".wav", cacheDir)
                    FileOutputStream(wavFile).use { fos ->
                        fos.write(pcmToWav(pcmBytes, sampleRate, 1, 16))
                    }

                    sendMeetingWavToServer(wavFile)
                }


            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    fun sendMeetingWavToServer(wavFile: File) {
        println("Sending WAV file to server: ${wavFile.absolutePath}, size=${wavFile.length()} bytes")

        CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            val maxRetries = 1
            var success = false

            // ✅ Create a unique transcript file for this session
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val transcriptFile = File(filesDir, "transcripted_meeting.txt")

            while (attempt <= maxRetries && !success) {
                try {
                    attempt++
                    println("Attempt $attempt to send file...")

                    val requestFile = wavFile.asRequestBody("audio/wav".toMediaType())
                    val body = MultipartBody.Part.createFormData("file", wavFile.name, requestFile)

                    val response: ResponseData = api.sendMeetingRecording(body)

                    val transcript = response.transcript
                    val responseText = response.response
                    val key = response.key

                    withContext(Dispatchers.Main) {
                        println("✅ Server response received (attempt $attempt): $transcript")
                        println("🔑 Raw key value (length=${key?.length}): '${key}'")
                        val key = response.key?.trim()?.lowercase()

                        when (key) {
                            "transcribed-meeting" -> {
                                println("✅ Transcribed meeting: ${response.response}")
                                meetingText.append(response.response + " ")

                                // ✅ Append to transcript file
                                transcriptFile.appendText(response.response + "\n")
                                println("📝 Appended to file: ${transcriptFile.absolutePath}")

                                // ✅ Show Toast
                                Toast.makeText(this@MainActivity, "Meeting ready to be summarized", Toast.LENGTH_SHORT).show()
                            }

                            else -> {
                                println("⚠️ Unknown or missing key: '${response.key}'")
                            }
                        }
                    }

                    success = true

                } catch (e: Exception) {
                    e.printStackTrace()
                    if (attempt > maxRetries) {
                        withContext(Dispatchers.Main) {
                            textDisplay.text = "⚠️ Failed to connect to server. Please try again."
                            statusTextView.text = "Error"
                            aiStatusButton.setImageResource(R.drawable.ai_processing)
                        }
                    } else {
                        println("Retrying... (attempt $attempt failed)")
                    }
                }
            }
        }
    }

    private fun askForServerIp(onIpEntered: (String) -> Unit) {
        val editText = EditText(this).apply {
            hint = "Enter server IP (e.g., 10.54.12.177)"
        }

        AlertDialog.Builder(this)
            .setTitle("Server IP")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotEmpty()) {
                    onIpEntered(ip)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun saveServerIp(ip: String) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putString("server_ip", ip)
            .apply()
    }

    private fun loadServerIp(): String? {
        return getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("server_ip", null)
    }

    private fun setApi(ip: String) {
        val baseUrl = "http://$ip:5000/"
        api = RetrofitClient.create(baseUrl)
    }

    fun sendMeetingFileToServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val meetingTranscriptedFile = File(filesDir, "transcripted_meeting.txt")

                if (!meetingTranscriptedFile.exists()) {
                    println("⚠️ Transcript file does not exist: ${meetingTranscriptedFile.absolutePath}")
                    return@launch
                }

                println("📤 Sending transcript file: ${meetingTranscriptedFile.absolutePath}, size=${meetingTranscriptedFile.length()} bytes")

                val requestFile = meetingTranscriptedFile.asRequestBody("text/plain".toMediaType())
                val body = MultipartBody.Part.createFormData(
                    "file",
                    meetingTranscriptedFile.name,
                    requestFile
                )

                val response: ResponseData = api.sendMeetingFile(body)

                withContext(Dispatchers.Main) {

                    println("✅ Server responded with: ${response.response}")
                    println("🔑 Key: ${response.key}, Transcript: ${response.transcript}")

                    when (response.key.trim().lowercase()) {
                        "summarized-meeting" -> {
                            //.text("\n\n📋 Summary: ${response.response}\n")
                            textDisplay.text = response.response
                        }
                        else -> {
                            println("⚠️ Unknown key in summary response: '${response.key}'")
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    textDisplay.text = "⚠️ Failed to send transcript file."
                }
            }
        }
    }

    fun outputActionBasedOnIntent(intent: String, speech: String) {
        print(intent)
        when(intent) {

            // ALL ABOUT CALENDAR
            "open_calendar" -> {
                println("📂 Opening calendar...")

                // Open Calendar → close Notes if open
                isCalendarOpen = true
                isNoteAppOpen = false
                scrollContainer.visibility = View.GONE
                noteAppLayout.visibility = View.GONE
                addNoteButton.visibility = View.GONE

                container.visibility = View.VISIBLE

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                textDisplay.visibility = View.GONE
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.visibility = View.VISIBLE
                currentAppDisplay.text = "Calendar App"
                closeAppButton.visibility = View.VISIBLE
            }
            "close_calendar" -> {
                println("📂 Closing calendar...")

                val today = LocalDate.now()
                showMonthCalendar(container, today.year, today.monthValue, today.dayOfMonth)

                // Close Calendar
                isCalendarOpen = false
                container.visibility = View.GONE

                aiImage.visibility = View.VISIBLE
                commandHint.visibility = View.VISIBLE
                textDisplay.visibility = View.GONE
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.visibility = View.GONE
                closeAppButton.visibility = View.GONE
            }
            "open_calendar_specific_month" -> {
                val regex = Regex("\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b", RegexOption.IGNORE_CASE)
                val match = regex.find(speech)

                val month = match?.value?.replaceFirstChar { it.uppercase() } // Capitalize first letter
                if (month != null) {
                    println("Extracted month: $month")
                } else {
                    println("No month found.")
                }

                try {
                    val monthName = month?.lowercase()
                    val months = arrayOf(
                        "January", "February", "March", "April", "May", "June",
                        "July", "August", "September", "October", "November", "December"
                    )

                    val monthIndex = months.indexOfFirst { it.equals(monthName, ignoreCase = true) }

                    if (monthIndex != -1) {
                        println("📅 Opening calendar for $monthName")

                        isCalendarOpen = true
                        isNoteAppOpen = false
                        scrollContainer.visibility = View.GONE
                        noteAppLayout.visibility = View.GONE
                        addNoteButton.visibility = View.GONE

                        container.visibility = View.VISIBLE

                        aiImage.visibility = View.GONE
                        commandHint.visibility = View.GONE
                        textDisplay.visibility = View.GONE
                        aiStatusButton.setImageResource(R.drawable.ai_processing)
                        statusTextView.text = "Sleeping..."
                        currentAppDisplay.visibility = View.VISIBLE
                        currentAppDisplay.text = "Calendar App"
                        closeAppButton.visibility = View.VISIBLE

                        // Show calendar for chosen month
                        val today = LocalDate.now()
                        currentYear = today.year
                        currentMonthIndex = monthIndex
                        showMonthCalendar(container, currentYear, monthIndex + 1, null)

                        speakText("Here’s your calendar for $monthName $currentYear")
                    } else {

                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }
            "show_event_on_calendar" -> {
                val regex = Regex(
                    """\b(?:(?<day>\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+)?)?(?<month>january|february|march|april|may|june|july|august|september|october|november|december)\b(?:\s*(?<day2>\d{1,2})(?:st|nd|rd|th)?)?""",
                    RegexOption.IGNORE_CASE
                )

                val match = regex.find(speech)

                var monthNum: Int? = null
                var dayNum: Int? = null

                if (match != null) {
                    val monthStr = match.groups["month"]?.value?.replaceFirstChar { it.uppercase() }
                    val dayStr = match.groups["day"]?.value ?: match.groups["day2"]?.value

                    val monthToInt = mapOf(
                        "January" to 1, "February" to 2, "March" to 3, "April" to 4,
                        "May" to 5, "June" to 6, "July" to 7, "August" to 8,
                        "September" to 9, "October" to 10, "November" to 11, "December" to 12
                    )

                    monthNum = monthStr?.let { monthToInt[it] }
                    dayNum = dayStr?.toIntOrNull()
                    println("Extracted month: $monthStr ($monthNum), day: $dayNum")
                } else {
                    println("No date found.")
                }

                val result = mutableMapOf<String, Int>()
                monthNum?.let { result["month"] = it }
                dayNum?.let { result["day"] = it }

                try {
                    val month = monthNum ?: -1
                    val day = dayNum ?: -1

                    if (month in 1..12) {
                        val months = arrayOf(
                            "January", "February", "March", "April", "May", "June",
                            "July", "August", "September", "October", "November", "December"
                        )
                        val monthName = months[month - 1]

                        println("📅 Opening calendar for $monthName ${if (day != -1) day else ""}")
                        speakText("Opening your calendar for $monthName ${if (day != -1) day else ""}")

                        val calendarData = loadCalendarData()

                        // Find the correct month object
                        val monthData = calendarData.months.find { it.name.equals(monthName, ignoreCase = true) }

                        if (monthData != null) {
                            if (day != -1) {
                                // Specific day
                                val dayData = monthData.days.find { it.day == day }
                                val dayEvents = dayData?.events ?: mutableListOf()

                                if (dayEvents.isNotEmpty()) {
                                    speakText("You have ${dayEvents.size} event${if (dayEvents.size > 1) "s" else ""} on $day $monthName")

                                    val eventTitles = dayEvents.map { it.title }.toTypedArray()

                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Events on $day $monthName")
                                        .setItems(eventTitles) { _, index ->
                                            val selectedEvent = dayEvents[index]

                                            AlertDialog.Builder(this@MainActivity)
                                                .setTitle(selectedEvent.title)
                                                .setMessage("Do you want to delete this event?")
                                                .setPositiveButton("Delete") { _, _ ->
                                                    dayEvents.removeAt(index)
                                                    saveCalendarData(calendarData)
                                                    Toast.makeText(this@MainActivity, "🗑️ Event deleted", Toast.LENGTH_SHORT).show()
                                                    speakText("Event deleted from $day $monthName")
                                                    showMonthCalendar(container, currentYear, month, highlightDay = day)
                                                }
                                                .setNegativeButton("Cancel", null)
                                                .show()
                                        }
                                        .setPositiveButton("Add New Event") { _, _ ->
                                            val input = EditText(this@MainActivity)
                                            AlertDialog.Builder(this@MainActivity)
                                                .setTitle("Add Event")
                                                .setView(input)
                                                .setPositiveButton("Save") { _, _ ->
                                                    val eventTitle = input.text.toString().ifBlank { "Untitled Event" }
                                                    dayEvents.add(EventData(eventTitle))
                                                    saveCalendarData(calendarData)
                                                    Toast.makeText(this@MainActivity, "📌 Event added on $day $monthName", Toast.LENGTH_SHORT).show()
                                                    speakText("Event added on $day $monthName")
                                                    showMonthCalendar(container, currentYear, month, highlightDay = day)
                                                }
                                                .setNegativeButton("Cancel", null)
                                                .show()
                                        }
                                        .setNegativeButton("Close", null)
                                        .show()

                                } else {
                                    speakText("No events found on $day $monthName. You can add one now.")

                                    val input = EditText(this@MainActivity)
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Add Event")
                                        .setView(input)
                                        .setPositiveButton("Save") { _, _ ->
                                            val eventTitle = input.text.toString().ifBlank { "Untitled Event" }
                                            dayData?.events?.add(EventData(eventTitle))
                                            saveCalendarData(calendarData)
                                            Toast.makeText(this@MainActivity, "📌 Event added on $day $monthName", Toast.LENGTH_SHORT).show()
                                            speakText("Event added on $day $monthName")
                                            showMonthCalendar(container, currentYear, month, highlightDay = day)
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                }
                            } else {
                                // Month-only request → show all events in month
                                val allEvents = monthData.days.flatMap { d ->
                                    d.events.map { "${d.day}: ${it.title}" }
                                }

                                if (allEvents.isNotEmpty()) {
                                    speakText("You have ${allEvents.size} events in $monthName")

                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle("Events in $monthName")
                                        .setItems(allEvents.toTypedArray(), null)
                                        .setPositiveButton("OK", null)
                                        .show()
                                } else {
                                    speakText("No events found in $monthName")
                                }
                            }
                        } else {
                            speakText("Sorry, I couldn't find month data for $monthName")
                        }
                    } else {
                        speakText("Sorry, I couldn’t read the date")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "delete_calendar_event" -> {
                // 🔹 Regex to extract month and day
                val dateRegex = Regex(
                    """\b(?:(?<day>\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+)?)?(?<month>january|february|march|april|may|june|july|august|september|october|november|december)\b(?:\s*(?<day2>\d{1,2})(?:st|nd|rd|th)?)?""",
                    RegexOption.IGNORE_CASE
                )

                // 🔹 Regex to extract title
                val titleRegex = Regex(
                    """(?:called|titled|title\s+is|with\s+(?:a|the)\s+title|about|named)\s+([^\n]+?)(?:\s+on\s|\s+at\s|$)""",
                    RegexOption.IGNORE_CASE
                )

                val dateMatch = dateRegex.find(speech)
                val titleMatch = titleRegex.find(speech)

                // Extract month and day
                val monthName = dateMatch?.groups?.get("month")?.value?.replaceFirstChar { it.uppercase() }
                val day = dateMatch?.groups?.get("day")?.value?.toIntOrNull()
                    ?: dateMatch?.groups?.get("day2")?.value?.toIntOrNull()

                // Map month to number
                val monthToInt = mapOf(
                    "January" to 1, "February" to 2, "March" to 3, "April" to 4,
                    "May" to 5, "June" to 6, "July" to 7, "August" to 8,
                    "September" to 9, "October" to 10, "November" to 11, "December" to 12
                )
                val numMonth = monthName?.let { monthToInt[it] }

                // Extract title
                val title = titleMatch?.groups?.get(1)?.value?.trim() ?: "Untitled Event"

                println("[DEBUG] Delete Event → Title: '$title', Month: $monthName ($numMonth), Day: $day")

                try {
                    if (numMonth != null && numMonth in 1..12 && day != null) {
                        val months = arrayOf(
                            "January", "February", "March", "April", "May", "June",
                            "July", "August", "September", "October", "November", "December"
                        )
                        val monthStr = months[numMonth - 1]

                        val calendarData = loadCalendarData()
                        val monthData = calendarData.months.find { it.name.equals(monthStr, ignoreCase = true) }

                        if (monthData != null) {
                            val dayData = monthData.days.find { it.day == day }

                            if (dayData != null) {
                                val removed = dayData.events.removeIf { it.title.equals(title, ignoreCase = true) }

                                if (removed) {
                                    saveCalendarData(calendarData)
                                    Toast.makeText(
                                        this@MainActivity,
                                        "🗑️ Event '$title' deleted from $day $monthStr",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    speakText("Event '$title' deleted from $day $monthStr")
                                    showMonthCalendar(container, currentYear, numMonth, highlightDay = day)
                                } else {
                                    speakText("I couldn’t find an event titled $title on $day $monthStr")
                                }
                            } else {
                                speakText("Sorry, I couldn’t find day $day in $monthStr")
                            }
                        } else {
                            speakText("Sorry, I couldn’t find month data for $monthStr")
                        }
                    } else {
                        println("❌ Could not parse month/day from speech: ${speech}")
                        speakText("Sorry, I couldn’t read the date")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "create_calendar_event" -> {
                // 🔹 Regex to extract month and day
                val dateRegex = Regex(
                    """\b(?:(?<day>\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+)?)?(?<month>january|february|march|april|may|june|july|august|september|october|november|december)\b(?:\s*(?<day2>\d{1,2})(?:st|nd|rd|th)?)?""",
                    RegexOption.IGNORE_CASE
                )

                // 🔹 Regex to extract title (handles "with a title" and "with the title")
                val titleRegex = Regex(
                    """(?:called|titled|title\s+is|with\s+(?:a|the)\s+title|about|named)\s+([^\n]+?)(?:\s+on\s|\s+at\s|$)""",
                    RegexOption.IGNORE_CASE
                )

                val dateMatch = dateRegex.find(speech)
                val titleMatch = titleRegex.find(speech)

                // Extract month and day
                val monthName = dateMatch?.groups?.get("month")?.value?.replaceFirstChar { it.uppercase() }
                val day = dateMatch?.groups?.get("day")?.value?.toIntOrNull()
                    ?: dateMatch?.groups?.get("day2")?.value?.toIntOrNull()

                // Map month to number
                val monthToInt = mapOf(
                    "January" to 1, "February" to 2, "March" to 3, "April" to 4,
                    "May" to 5, "June" to 6, "July" to 7, "August" to 8,
                    "September" to 9, "October" to 10, "November" to 11, "December" to 12
                )
                val numMonth = monthName?.let { monthToInt[it] }

                // Extract title
                val title = titleMatch?.groups?.get(1)?.value?.trim() ?: "Untitled Event"

                println("[DEBUG] Extracted title: '$title' from speech: '$speech'")
                println("[DEBUG] Month: $monthName ($numMonth), Day: $day")

                // Build JSON-ready map
                val eventCalendar = mutableMapOf<String, Any>()
                numMonth?.let { eventCalendar["month"] = it }
                day?.let { eventCalendar["day"] = it }
                eventCalendar["title"] = title

                try {
                    val monthInt = numMonth
                    val dayInt = day

                    if (monthInt != null && monthInt in 1..12 && dayInt != null) {
                        val months = arrayOf(
                            "January", "February", "March", "April", "May", "June",
                            "July", "August", "September", "October", "November", "December"
                        )
                        val monthDisplayName = months[monthInt - 1]

                        val calendarData = loadCalendarData()
                        val monthData = calendarData.months.find { it.name.equals(monthDisplayName, ignoreCase = true) }

                        if (monthData != null) {
                            val dayData = monthData.days.find { it.day == dayInt }

                            if (dayData != null) {
                                dayData.events.add(EventData(title))
                                saveCalendarData(calendarData)

                                Toast.makeText(
                                    this@MainActivity,
                                    "📌 Event '$title' added on $dayInt $monthDisplayName",
                                    Toast.LENGTH_SHORT
                                ).show()
                                speakText("Event '$title' added on $dayInt $monthDisplayName")
                                showMonthCalendar(container, currentYear, monthInt, highlightDay = dayInt)
                            } else {
                                speakText("Sorry, I couldn’t find day $dayInt in $monthDisplayName")
                            }
                        } else {
                            speakText("Sorry, I couldn’t find month data for $monthDisplayName")
                        }
                    } else {
                        speakText("Sorry, I couldn’t read the date")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // ALL ABOUT NOTES
            "open_notes" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.text = "Note App"

                scrollContainer.visibility = View.VISIBLE

                textDisplay.visibility = View.GONE
                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                noteAppLayout.visibility = View.VISIBLE
                currentAppDisplay.visibility = View.VISIBLE

                addNoteButton.visibility = View.VISIBLE
            }
            "close_note" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                aiImage.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                commandHint.text = "Say... Hey Aida!"

                closeAllApps()

                scrollContainer.visibility = View.GONE

                textDisplay.visibility = View.GONE
                aiImage.visibility = View.VISIBLE
                commandHint.visibility = View.VISIBLE
                noteAppLayout.visibility = View.GONE
                currentAppDisplay.visibility = View.GONE

                addNoteButton.visibility = View.GONE
            }
            "create_note" -> {
                // 🔹 Regex to extract note title
                val titleRegex = Regex(
                    """(?:called|titled|title\s+is|with\s+the\s+title|about|named)\s+([^\n]+?)(?:$|\s+and\s+content\s+is\s+|$)""",
                    RegexOption.IGNORE_CASE
                )

                // 🔹 Regex to extract note content
                val contentRegex = Regex(
                    """(?:content\s+is|with\s+content|body\s+is)\s+([^\n]+)""",
                    RegexOption.IGNORE_CASE
                )

                val titleMatch = titleRegex.find(speech)
                val contentMatch = contentRegex.find(speech)

                val title = titleMatch?.groups?.get(1)?.value?.trim() ?: "Untitled Note"
                val content = contentMatch?.groups?.get(1)?.value?.trim() ?: ""

                println("[DEBUG] Extracted Title: '$title', Content: '$content' from speech: '$speech'")

                val noteData = mutableMapOf<String, Any>()
                noteData["title"] = title
                if (content.isNotEmpty()) noteData["content"] = content

                try {
                    val title = title
                    val content = content
                    aiStatusButton.setImageResource(R.drawable.ai_processing)
                    statusTextView.text = "Sleeping..."

                    scrollContainer.visibility = View.VISIBLE

                    textDisplay.visibility = View.GONE
                    aiImage.visibility = View.GONE
                    commandHint.visibility = View.GONE
                    noteAppLayout?.visibility = View.VISIBLE

                    addNoteButton.visibility = View.VISIBLE

                    aiCreateNewNote(title, content)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "delete_note" -> {
                // 🔹 Regex to extract note title
                val titleRegex = Regex(
                    """(?:called|titled|title\s+is|with\s+the\s+title|about|named)\s+([^\n]+?)(?:$|\s+and\s+content\s+is\s+|$)""",
                    RegexOption.IGNORE_CASE
                )

                val titleMatch = titleRegex.find(speech)
                val title = titleMatch?.groups?.get(1)?.value?.trim() ?: "Untitled Note"

                println("[DEBUG] Extracted Title to Delete: '$title' from speech: '$speech'")

                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                currentAppDisplay.text = "Note App"

                scrollContainer.visibility = View.VISIBLE
                textDisplay.visibility = View.GONE
                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                noteAppLayout?.visibility = View.VISIBLE
                currentAppDisplay.visibility = View.VISIBLE
                addNoteButton.visibility = View.VISIBLE

                try {
                    if (title.isNotEmpty()) {
                        deleteNoteByTitle(this@MainActivity, "notes.json", title)
                        loadNotesFromJson()
                        textDisplay.text = "Deleted note: $title"
                    } else {
                        textDisplay.text = "No note title provided"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // ALL ABOUT ALARMS
            "set_alarm" -> {
                print("Start alarm")

                // 🔹 Regex to extract time
                val timeRegex = Regex(
                    """(?<hour>\d{1,2})(?:[:.\s](?<minute>\d{2}))?\s*(?<ampm>am|pm|a\.m\.|p\.m\.|morning|night|evening)?""",
                    RegexOption.IGNORE_CASE
                )

                val timeMatch = timeRegex.find(speech)

                var hour: Int? = null
                var minute: Int? = null
                var ampm: String? = null

                if (timeMatch != null) {
                    hour = timeMatch.groups["hour"]?.value?.toIntOrNull()
                    minute = timeMatch.groups["minute"]?.value?.toIntOrNull() ?: 0

                    val ampmRaw = timeMatch.groups["ampm"]?.value?.lowercase()
                    ampm = when {
                        ampmRaw == null -> null
                        "a" in ampmRaw || "morning" in ampmRaw -> "AM"
                        "p" in ampmRaw || "night" in ampmRaw || "evening" in ampmRaw -> "PM"
                        else -> null
                    }
                }

                println("[DEBUG] Set Alarm → Time: ${hour ?: "--"}:${minute?.toString()?.padStart(2, '0') ?: "--"} ${ampm ?: ""}")

                val alarmData = mutableMapOf<String, Any>()
                hour?.let { alarmData["hour"] = it }
                minute?.let { alarmData["minute"] = it }
                ampm?.let { alarmData["ampm"] = it }

                try {
                    val hour = hour
                    val minute = minute
                    val ampm = ampm

                    var isAmPm = false
                    if(ampm == "AM") {
                        isAmPm = true
                    }

                    if (hour != null && minute != null) {
                        createAlarmDirectly(this@MainActivity, hour, minute, isAmPm)
                        speakText("Alarm set for $hour:$minute $ampm")
                    } else {
                        speakText("Sorry, I couldn’t read the time")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // THIS ALL ABOUT SEARCHING ONLINE
            "search_online" -> {
                if(isConnectedToServer) {
                    aiStatusButton.setImageResource(R.drawable.ai_processing)
                    statusTextView.text = "Sleeping..."

                    scrollContainer.visibility = View.VISIBLE

                    textDisplay.visibility = View.VISIBLE
                    textDisplay.text = "Loading result..."
                    aiImage.visibility = View.GONE
                    commandHint.visibility = View.GONE
                    noteAppLayout?.visibility = View.GONE

                    textDisplay.text = "Processing..."

                    sendSearchQueryToServer(speech)
                } else {
                    showConnectionErrorDialog()
                }
            }
            "close_search" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                aiImage.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                commandHint.text = "Say... Hey Aida!"

                scrollContainer.visibility = View.GONE

                textDisplay.visibility = View.GONE
                aiImage.visibility = View.VISIBLE
                commandHint.visibility = View.VISIBLE
                noteAppLayout?.visibility = View.GONE
                currentAppDisplay.visibility = View.GONE

                addNoteButton.visibility = View.GONE
            }

            // LIVE MEETING SHEETS
            "start_live_meeting" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."

                closeAllApps()

                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE

                currentAppDisplay.text = "Meeting Mode"
                meetingModeDisplay.visibility = View.VISIBLE

                isRecordingMeeting = true

                print("Recording started")

                val playBtn = findViewById<ImageButton>(R.id.playButton)
                val stopBtn = findViewById<ImageButton>(R.id.stopButton)

                playBtn.visibility = View.GONE
                stopBtn.visibility = View.VISIBLE
                startLiveMeetingRecording()
            }
            "stop_live_meeting" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."

                val playBtn = findViewById<ImageButton>(R.id.playButton)
                val stopBtn = findViewById<ImageButton>(R.id.stopButton)

                playBtn.visibility = View.VISIBLE
                stopBtn.visibility = View.GONE

                isRecordingMeeting = false
            }
            "summarize_meeting" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."

                closeAllApps()

                scrollContainer.visibility = View.VISIBLE

                textDisplay.visibility = View.VISIBLE
                textDisplay.text = "Loading result..."
                aiImage.visibility = View.GONE
                commandHint.visibility = View.GONE
                noteAppLayout.visibility = View.GONE

                textDisplay.text = "Processing..."

                sendMeetingFileToServer()
            }

            "introduce_yourself" -> {
                aiStatusButton.setImageResource(R.drawable.ai_processing)
                statusTextView.text = "Sleeping..."
                aiImage.setImageResource(R.drawable.ai_processing)

                speakText("Hi, I am eye the. an Artificial Intelligence designed to assist for common or redundant tasks such as note taking. alarm creation. creation of calendar event. transcribing a meeting. summarizing the meeting. and searching online. I am created and developed by these wonderful developers. Their names are. Mercado. Ah Gil Yon. Knee Pie. Pa see no. Ferrer. and Villareal. Finally. if you want me to assist you. don't forget to say")
            }
        }
    }

    fun sendSearchQueryToServer(queryText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = api.sendSearchQuery(queryText)

                withContext(Dispatchers.Main) {
                    println("✅ Server responded with: ${response.response}")
                    textDisplay.text = response.response
                    speakText(response.response)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    textDisplay.text = "⚠️ Failed to send query."
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.stop()
        porcupineManager?.delete()
        soundPool.release()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        aiImage.setImageResource(R.drawable.ai_processing)
        commandHint.text = "Say... Hey Aida!"
    }

}
