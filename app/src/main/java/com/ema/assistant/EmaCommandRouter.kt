package com.ema.assistant
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
class EmaCommandRouter(private val context:Context,private val tts:TextToSpeech){
 fun execute(raw:String,done:(String)->Unit){val c=raw.trim();val l=c.lowercase();when{
  l.startsWith("open youtube")->openSearch("com.google.android.youtube",c.drop(12).trim(),"https://www.youtube.com/results?search_query=")
  l.startsWith("open spotify")->openSearch("com.spotify.music",c.drop(12).trim(),"https://open.spotify.com/search/")
  l.startsWith("open ")->openKnownApp(c.substringAfter("open ").trim())
  l.startsWith("search ")->{val q=c.substringAfter("search ").trim();context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query",q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));speak("Searching for $q");done("Searching for $q")}
  else->CentralHubClient(context).ask(c){r->speak(r);done(r)}
 }}
 private fun openSearch(pkg:String,q:String,base:String){val launch=context.packageManager.getLaunchIntentForPackage(pkg);if(launch==null){speak("That app is not installed.");return};context.startActivity(launch);if(q.isNotBlank())context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base+Uri.encode(q))));speak(if(q.isBlank())"Opening it." else "Opening it and searching for $q.")}
 private fun openKnownApp(name:String){val apps=context.packageManager.getInstalledApplications(0);val m=apps.firstOrNull{it.loadLabel(context.packageManager).toString().equals(name,true)};if(m==null){speak("I couldn't find $name on this phone.");return};context.startActivity(context.packageManager.getLaunchIntentForPackage(m.packageName));speak("Opening $name.")}
 private fun speak(s:String){tts.speak(s,TextToSpeech.QUEUE_FLUSH,null,"ema")}
}
