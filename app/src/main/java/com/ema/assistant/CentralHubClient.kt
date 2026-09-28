package com.ema.assistant
import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread
class CentralHubClient(private val context:Context){
 companion object{private const val HUB_URL="https://REPLACE_WITH_MAYOBUILD_ORIGIN/api/ai-tunnel";private const val APP_ID="ema"}
 fun ask(message:String,callback:(String)->Unit){thread{
  try{
   val p=context.getSharedPreferences("ema_tunnel",Context.MODE_PRIVATE)
   val id=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)?:UUID.randomUUID().toString()
   var token=p.getString("token",null)
   if(token==null){val r=post(null,mapOf("action" to "register","appId" to APP_ID,"deviceId" to id,"deviceLabel" to android.os.Build.MODEL));token=Regex("\"token\":\"([^\"]+)\"").find(r)?.groupValues?.get(1);if(!token.isNullOrBlank())p.edit().putString("token",token).apply()}
   if(token.isNullOrBlank()){callback("Central Hub registration failed.");return@thread}
   val r=post(token,mapOf("action" to "ask","appId" to APP_ID,"deviceId" to id,"message" to message))
   val a=Regex("\"answer\":\"((?:\\\\.|[^\"])*)\"").find(r)?.groupValues?.get(1)?.replace("\\n","\n")?.replace("\\"","\"")?.replace("\\\\","\\")
   callback(a?:"Central Hub did not return an answer.")
  }catch(_:Exception){callback("I can still handle phone actions, but the Central Hub is unavailable right now.")}
 }}
 private fun post(token:String?,body:Map<String,String>):String{val c=URL(HUB_URL).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=20000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(token!=null)c.setRequestProperty("Authorization","Bearer $token");val j=body.entries.joinToString(",","{","}"){"\""+esc(it.key)+"\":\""+esc(it.value)+"\""};c.outputStream.use{it.write(j.toByteArray())};val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;return s.bufferedReader().use{it.readText()}}
 private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"")
}
