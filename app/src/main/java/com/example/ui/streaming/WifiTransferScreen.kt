package com.example.ui.streaming

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.SafePlayerApp
import com.example.security.SecurityAction
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiTransferScreen(
    app: SafePlayerApp,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val server = app.wifiShareServer
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(server.isRunning) }
    var serverUrl by remember { mutableStateOf(if (server.isRunning) "http://${server.getIpAddress()}:8080" else "") }
    var pairingCode by remember { mutableStateOf(server.pairingCode.orEmpty()) }
    var statusText by remember { mutableStateOf(if (server.isRunning) "الخادم جاهز؛ أدخل رمز الاقتران على الجهاز الآخر" else "الخادم متوقف") }

    DisposableEffect(server, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && server.isRunning) {
                server.stop { running, message ->
                    isRunning = running
                    serverUrl = ""
                    pairingCode = ""
                    statusText = message
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (server.isRunning) {
                server.stop { _, _ -> }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المشاركة والنقل عبر Wi-Fi 📡", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Icon & Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRunning) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = if (isRunning) "مشاركة Wi-Fi نشطة" else "مشاركة Wi-Fi متوقفة",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    // Start / Stop Toggle Button
                    Button(
                        onClick = {
                            if (isRunning) {
                                server.stop { running, msg ->
                                    isRunning = running
                                    statusText = msg
                                    serverUrl = ""
                                    pairingCode = ""
                                }
                            } else {
                                scope.launch {
                                    if (!app.securityManager.canPerformAction(SecurityAction.SHARE)) {
                                        Toast.makeText(context, "المشاركة محظورة بسبب القفل أو الرقابة الأبوية", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    server.start { running, urlOrMsg ->
                                        isRunning = running
                                        if (running) {
                                            serverUrl = urlOrMsg
                                            pairingCode = server.pairingCode.orEmpty()
                                            statusText = "أدخل رمز الاقتران الظاهر أدناه على الجهاز الآخر"
                                        } else {
                                            serverUrl = ""
                                            pairingCode = ""
                                            statusText = urlOrMsg
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRunning) "إيقاف الخادم" else "تشغيل خادم المشاركة الآن")
                    }
                }
            }

            // Connection Details Card (When Running)
            AnimatedVisibility(visible = isRunning && serverUrl.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("رابط التصفح والنقل المباشر:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }

                        Spacer(Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = serverUrl,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("رمز الاقتران المؤقت:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pairingCode.ifBlank { "--------" },
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                letterSpacing = 8.sp
                            )
                        }
                        Text(
                            "أدخل الرمز على الجهاز الآخر. استخدم شبكة موثوقة فقط، وأوقف الخادم عند الانتهاء؛ اتصال HTTP المحلي غير مشفّر.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "افتح هذا الرابط على كمبيوترك لنقل الوسائط ومشاهدتها من الهاتف:\n$serverUrl")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "مشاركة رابط الخادم"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("مشاركة الرابط")
                            }
                        }
                    }
                }
            }

            // How it works Instructions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("💡 كيف يعمل النقل اللاسلكي؟", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    InstructionStep(number = "1", text = "تأكد من اتصال هاتفك وجهاز الكمبيوتر أو التلفاز بنفس شبكة الـ Wi-Fi.")
                    InstructionStep(number = "2", text = "اضغط على زر (تشغيل خادم المشاركة الآن) أعلاه.")
                    InstructionStep(number = "3", text = "افتح الرابط على جهاز موثوق وأدخل رمز الاقتران المؤقت الظاهر أعلاه.")
                    InstructionStep(number = "4", text = "يمكنك تشغيل أو تنزيل الوسائط غير المحمية ورفع ملف حتى 1 GiB إلى مساحة SafePlayer الخاصة. عناصر الخزنة لا تُشارك.")
                    InstructionStep(number = "5", text = "الخادم يتوقف عند مغادرة الشاشة؛ أوقفه يدوياً فور الانتهاء. الشبكة المحلية تستخدم HTTP غير مشفّر.")
                }
            }
        }
    }
}

@Composable
fun InstructionStep(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
