package com.ema.assistant
import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
 private lateinit var tts: TextToSpeech
 private lateinit var status: TextView
 private var recognizer: SpeechRecognizer? = null
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState); tts=TextToSpeech(this,this)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,60,40,40)}
  status=TextView(this).apply{text="EMA is ready";textSize=24f}; root.addView(status)
  root.addView(Button(this).apply{text="Talk to EMA";setOnClickListener{listenOnce()}})
  root.addView(Button(this).apply{text="Set EMA as digital assistant";setOnClickListener{requestAssistantRole()}})
  setContentView(root)
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10)
 }
 private fun requestAssistantRole(){val rm=getSystemService(RoleManager::class.java);if(rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)&&!rm.isRoleHeld(RoleManager.ROLE_ASSISTANT))startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),20)else status.text="EMA is already the assistant, or this device does not expose the assistant role."}
 private fun listenOnce(){
  if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Speech recognition is unavailable.";return}
  recognizer?.destroy(); recognizer=SpeechRecognizer.createSpeechRecognizer(this).also{sr->
   sr.setRecognitionListener(object:android.speech.RecognitionListener{
    override fun onResults(b:Bundle?){val c=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull();if(c.isNullOrBlank())onError(0)else{status.text="You: $c";EmaCommandRouter(this@MainActivity,tts).execute(c){status.text=it}}}
    override fun onError(e:Int){status.text="I couldn't hear that. Try again."}
    override fun onReadyForSpeech(p:Bundle?){}
    override fun onBeginningOfSpeech(){}
    override fun onRmsChanged(v:Float){}
    override fun onBufferReceived(b:ByteArray?){}
    override fun onEndOfSpeech(){}
    override fun onPartialResults(b:Bundle?){}
    override fun onEvent(t:Int,b:Bundle?){}
   })
  }
  recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault())})
 }
 override fun onInit(code:Int){if(code==TextToSpeech.SUCCESS)tts.language=Locale.getDefault()}
 override fun onDestroy(){recognizer?.destroy();tts.shutdown();super.onDestroy()}
}
