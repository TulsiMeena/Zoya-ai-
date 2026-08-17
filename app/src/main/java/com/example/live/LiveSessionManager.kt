package com.example.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.decodeBase64
import java.util.concurrent.TimeUnit

enum class ZoyaState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class LiveSessionManager(
    private val context: Context,
    private val toolEngine: ToolExecutionEngine,
    private val onAudioOut: (ByteArray) -> Unit,
    private val onInterrupt: () -> Unit = {}
) {
    private val _zoyaState = MutableStateFlow(ZoyaState.IDLE)
    val zoyaState: StateFlow<ZoyaState> = _zoyaState.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val json = Json { ignoreUnknownKeys = true }
    
    // Tools definition
    private val toolsJson = buildJsonObject {
        putJsonArray("functionDeclarations") {
            add(buildJsonObject {
                put("name", "openApp")
                put("description", "Open an application package, like WhatsApp or YouTube")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("packageName") {
                            put("type", "STRING")
                            put("description", "A generic name of the app to launch (e.g. 'WhatsApp', 'YouTube', 'Settings', 'Calculator')")
                        }
                    }
                    putJsonArray("required") { add("packageName") }
                }
            })
            add(buildJsonObject {
                put("name", "searchAndCallContact")
                put("description", "Search for a contact name on the device and call them. Can optionally open dialer instead of calling immediately, or use a specific SIM card slot.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The EXACT name of the contact as spoken by the user. NEVER guess or invent numbers. If the user says a name, use exactly that name.")
                        }
                        putJsonObject("useDialer") {
                            put("type", "BOOLEAN")
                            put("description", "Set to true if user wants to open dial pad / keyboard so they can see the number before calling")
                        }
                        putJsonObject("simSlot") {
                            put("type", "INTEGER")
                            put("description", "1 for SIM 1, 2 for SIM 2 if user specified. Null if default.")
                        }
                    }
                    putJsonArray("required") { add("contactName") }
                }
            })
            add(buildJsonObject {
                put("name", "sendWhatsAppMessage")
                put("description", "Send a WhatsApp message to a specific contact with some text.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The EXACT name of the contact as spoken by the user. NEVER guess or invent numbers. If the user says a name, use exactly that name.")
                        }
                        putJsonObject("message") {
                            put("type", "STRING")
                        }
                    }
                    putJsonArray("required") { add("contactName"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "sendGmail")
                put("description", "Draft or send an email.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recipientEmail") { put("type", "STRING") }
                        putJsonObject("subject") { put("type", "STRING") }
                        putJsonObject("body") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("recipientEmail"); add("subject"); add("body") }
                }
            })
            add(buildJsonObject {
                put("name", "searchYouTube")
                put("description", "Search for a query on YouTube app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "adjustVolume")
                put("description", "Adjust the device volume.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") { 
                            put("type", "STRING") 
                            put("description", "Volume action: 'up', 'down', 'mute', 'unmute', or 'max'")
                        }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "setVolumePercent")
                put("description", "Set the device volume to a specific percentage (0 to 100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("percent") { 
                            put("type", "INTEGER") 
                            put("description", "Volume percentage (0-100)")
                        }
                    }
                    putJsonArray("required") { add("percent") }
                }
            })
            add(buildJsonObject {
                put("name", "getSimCardInfo")
                put("description", "Check how many active SIM cards the device has.")
            })
            add(buildJsonObject {
                put("name", "openQuickSettings")
                put("description", "Pull down the quick settings / components panel (toggles for wifi, bluetooth, etc).")
            })
            add(buildJsonObject {
                put("name", "clickTextOnScreen")
                put("description", "Click on any text visible on the screen. Acts like a real human finger tap and shows tap effect visually.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "STRING")
                            put("description", "The text to tap on the screen")
                        }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "openNotificationPanel")
                put("description", "Pull down the notification bar / status bar to view notifications.")
            })
            add(buildJsonObject {
                put("name", "toggleTorch")
                put("description", "Turn the flashlight/torch on or off.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("state") { 
                            put("type", "STRING") 
                            put("description", "'on' or 'off'")
                        }
                    }
                    putJsonArray("required") { add("state") }
                }
            })
            add(buildJsonObject {
                put("name", "setBrightness")
                put("description", "Set the screen brightness. Note: Requires write settings permission first.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("level") { 
                            put("type", "INTEGER") 
                            put("description", "Brightness level 0 to 100")
                        }
                    }
                    putJsonArray("required") { add("level") }
                }
            })
            add(buildJsonObject {
                put("name", "playMedia")
                put("description", "Play media (like a song, video, or movie) from another app by searching for it.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { 
                            put("type", "STRING") 
                            put("description", "What to play (e.g. 'Despacito by Luis Fonsi' or 'latest tech news')")
                        }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
        }
    }

    fun startSession() {
        if (webSocket != null) return
        
        val prefs = context.getSharedPreferences("ZoyaPrefs", android.content.Context.MODE_PRIVATE)
        var apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty() && BuildConfig.GEMINI_API_KEY.isNotBlank() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY") {
            apiKey = BuildConfig.GEMINI_API_KEY
        }
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "YOUR_API_KEY") {
            Log.e("ZoyaDiagnostic", "No API Key found")
            addMessage("Error: Gemini API Key is missing. Please set it in Settings or Secrets tab.")
            _zoyaState.value = ZoyaState.IDLE
            return
        }
        
        Log.i("ZoyaDiagnostic", "Connecting to Gemini Live API...")
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("ZoyaDiagnostic", "WebSocket connection OPENED successfully.")
                addMessage("Connected to Gemini Live. Initializing...")
                sendSetupMessage(webSocket)
                _zoyaState.value = ZoyaState.LISTENING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("ZoyaDiagnostic", "WebSocket Text Msg Received (length: ${text.length})")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val text = bytes.utf8()
                Log.d("ZoyaDiagnostic", "WebSocket Binary Msg Received (utf8 length: ${text.length})")
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorBody = response?.body?.string() ?: "No body"
                Log.e("ZoyaDiagnostic", "WebSocket ERROR: ${t.message}, Response: $errorBody", t)
                addMessage("Connection error: ${t.message}")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
                isSetupComplete = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("ZoyaDiagnostic", "WebSocket CLOSED. Code: $code, Reason: $reason")
                addMessage("Connection closed: $reason")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
                isSetupComplete = false
            }
        })
    }

    private fun sendInitialPrompt(ws: WebSocket) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", "Hi Zoya! Introduce yourself briefly.")
                            })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        ws.send(msg.toString())
    }

    private fun addMessage(msg: String) {
        _messages.value = _messages.value + msg
    }

    fun stopSession() {
        webSocket?.close(1000, "User stopped")
        webSocket = null
        _zoyaState.value = ZoyaState.IDLE
        addMessage("Session stopped.")
    }

    fun sendTextMessage(text: String) {
        if (webSocket == null || !isSetupComplete || _zoyaState.value == ZoyaState.IDLE) return
        addMessage("You: $text")
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", text) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        webSocket?.send(msg.toString())
    }
    
    fun sendAudioData(pcmData: ShortArray, length: Int) {
        if (webSocket == null || _zoyaState.value == ZoyaState.IDLE) {
            return
        }
        
        Log.v("ZoyaDiagnostic", "Sending audio chunk size=${length} to Gemini")
        // Convert ShortArray to ByteArray (Little Endian)
        val byteArray = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = pcmData[i]
            byteArray[i * 2] = (s.toInt() and 0x00FF).toByte()
            byteArray[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        
        val base64Data = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        
        val inputMsg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    add(buildJsonObject {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    })
                }
            }
        }
        webSocket?.send(inputMsg.toString())
    }
    
    private fun sendSetupMessage(ws: WebSocket) {
        val setupMsg = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/gemini-2.0-flash-exp")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Aoede")
                            }
                        }
                    }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", "You are Zoya, a fast, helpful AI assistant on the user's Android phone. \nCRITICAL RULE: DO NOT output any internal thinking, planning, or narration. NEVER say what you are going to do before doing it. JUST CALL THE TOOL IN SILENCE. Keep your verbal responses EXTREMELY short, brief, and NEVER repeat yourself. Do not use filler words.\n\nCRITICAL: DO NOT INVENT NUMBERS. NEVER DIAL 121. If the user asks to call someone by name (e.g. 'Shivank' or 'Rahul'), you MUST pass their EXACT NAME into the contactName parameter of the tool. The tool will find the number automatically! If you don't know the name, ask the user. DO NOT GUESS NUMBERS.\n\nCALLING INSTRUCTIONS:\nWhen asked to call, DO NOT explain your plan. 1. use getSimCardInfo. 2. use searchAndCallContact with useDialer=true FIRST. This opens the dialer, entirely overwrites/clears any old number, and types the new number so the user can verify it safely. 3. Verbally say ONLY ONCE: 'Maine number enter kar diya hai. [Ask for SIM if 2 SIMs present: Kaunse SIM me balance hai, 1 ya 2? Agar confirm hai to call laga du?]' 4. AFTER user confirms, use searchAndCallContact with useDialer=false and simSlot to instantly start the call.\n\nUI ACTIONS:\nTo do real human-like clicks that show onscreen, use clickTextOnScreen, openNotificationPanel, or openQuickSettings.\nIf asked to turn on torch, use toggleTorch. If asked to change brightness, use setBrightness. If asked to set volume, use setVolumePercent. If asked for camera or other apps, use openApp.")
                        })
                    }
                }
                putJsonArray("tools") {
                    add(toolsJson)
                }
            }
        }
        ws.send(setupMsg.toString())
    }

    private var isSetupComplete = false

    private fun handleServerMessage(text: String) {
        Log.d("LiveSessionManager", "Server msg: $text")
        try {
            val jsonMsg = json.parseToJsonElement(text).jsonObject
            
            // Check setup complete in both camelCase and snake_case
            val isSetupDone = jsonMsg.containsKey("setupComplete") || jsonMsg.containsKey("setup_complete")
            if (isSetupDone) {
                isSetupComplete = true
                Log.i("ZoyaDiagnostic", "Gemini Live Setup Completed successfully.")
                addMessage("Connected! Initializing conversation...")
                sendInitialPrompt(webSocket!!)
            }

            // Check serverContent / server_content
            val serverContent = jsonMsg["serverContent"]?.jsonObject ?: jsonMsg["server_content"]?.jsonObject
            if (serverContent != null) {
                val modelTurn = serverContent["modelTurn"]?.jsonObject ?: serverContent["model_turn"]?.jsonObject
                
                val isInterrupted = serverContent["interrupted"]?.jsonPrimitive?.booleanOrNull == true ||
                                    serverContent["interrupted"]?.jsonPrimitive?.content == "true"
                if (isInterrupted) {
                    onInterrupt()
                }
                
                val parts = modelTurn?.get("parts")?.jsonArray
                parts?.forEach { partElement ->
                    val part = partElement.jsonObject
                    
                    val inlineData = part["inlineData"]?.jsonObject ?: part["inline_data"]?.jsonObject
                    if (inlineData != null) {
                        val dataBase64 = inlineData["data"]?.jsonPrimitive?.content
                        if (dataBase64 != null) {
                            _zoyaState.value = ZoyaState.SPEAKING
                            val rawBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                            onAudioOut(rawBytes)
                        }
                    }

                    if (part.containsKey("text")) {
                        val textContent = part["text"]?.jsonPrimitive?.content
                        if (!textContent.isNullOrBlank()) {
                            addMessage("Zoya: $textContent")
                        }
                    }
                }
                
                val isTurnComplete = serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true ||
                                     serverContent["turnComplete"]?.jsonPrimitive?.content == "true" ||
                                     serverContent["turn_complete"]?.jsonPrimitive?.booleanOrNull == true ||
                                     serverContent["turn_complete"]?.jsonPrimitive?.content == "true"
                if (isTurnComplete) {
                    _zoyaState.value = ZoyaState.LISTENING
                }
            }
            
            // Check toolCall / tool_call
            val toolCallObj = jsonMsg["toolCall"]?.jsonObject ?: jsonMsg["tool_call"]?.jsonObject
            if (toolCallObj != null) {
                val functionCalls = toolCallObj["functionCalls"]?.jsonArray ?: toolCallObj["function_calls"]?.jsonArray
                
                functionCalls?.forEach { callElement ->
                    val callObj = callElement.jsonObject
                    val id = callObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = callObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = callObj["args"]?.jsonObject ?: buildJsonObject { }
                    
                    addMessage("Action: Executing $name")
                    executeToolAndRespond(id, name, args)
                }
            }
        } catch (e: Exception) {
            Log.e("LiveSessionManager", "Error parsing server message", e)
            addMessage("Parsing error: ${e.message}")
        }
    }
    
    private fun executeToolAndRespond(id: String, name: String, args: JsonObject) {
         _zoyaState.value = ZoyaState.THINKING
         scope.launch {
              val resultStr = toolEngine.execute(name, args)
              
              val responseMsg = buildJsonObject {
                  putJsonObject("toolResponse") {
                      putJsonArray("functionResponses") {
                          add(buildJsonObject {
                              put("id", id)
                              put("name", name)
                              putJsonObject("response") {
                                  put("result", resultStr)
                              }
                          })
                      }
                  }
              }
              webSocket?.send(responseMsg.toString())
         }
    }
}
