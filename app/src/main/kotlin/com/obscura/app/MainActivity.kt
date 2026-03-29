package com.obscura.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.obscura.kit.*
import com.obscura.kit.network.LoginScenario
import com.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ObscuraApp

        setContent {
            MaterialTheme {
                var client by remember { mutableStateOf(app.client) }
                val authState = client?.authState?.collectAsState()?.value ?: AuthState.LOGGED_OUT

                if (client != null && authState == AuthState.AUTHENTICATED) {
                    ConnectedScreen(client!!, app)
                } else {
                    LoginScreen(app) { client = it }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(app: ObscuraApp, onClient: (ObscuraClient) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        Text("Register / Login", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(value = username, onValueChange = { username = it },
            label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("Password (12+ chars)") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (username.isBlank() || password.length < 12) return@Button
                scope.launch {
                    status = "Registering..."
                    try {
                        val c = withContext(Dispatchers.IO) {
                            val c = app.createClient(username)
                            c.register(username, password)
                            c.connect()
                            c
                        }
                        app.client = c
                        app.saveSession()
                        onClient(c)
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    }
                }
            }) { Text("Register") }

            Button(onClick = {
                if (username.isBlank() || password.length < 12) return@Button
                scope.launch {
                    status = "Logging in..."
                    try {
                        val c = withContext(Dispatchers.IO) {
                            val c = app.createClient(username)
                            val result = c.login(username, password)
                            when (result.scenario) {
                                LoginScenario.EXISTING_DEVICE -> {
                                    c.connect()
                                }
                                LoginScenario.NEW_DEVICE -> {
                                    c.loginAndProvision(username, password)
                                    c.connect()
                                }
                                LoginScenario.DEVICE_MISMATCH -> {
                                    c.wipeDevice()
                                    c.loginAndProvision(username, password)
                                    c.connect()
                                }
                                LoginScenario.INVALID_CREDENTIALS -> throw Exception("Wrong password")
                                LoginScenario.USER_NOT_FOUND -> throw Exception("User not found — register first")
                            }
                            c
                        }
                        app.client = c
                        app.saveSession()
                        onClient(c)
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        status = "Error: ${e.message}"
                    }
                }
            }) { Text("Login") }
        }
    }
}

@Composable
fun ConnectedScreen(client: ObscuraClient, app: ObscuraApp) {
    val scope = rememberCoroutineScope()
    val friends by client.friendList.collectAsState()
    val pending by client.pendingRequests.collectAsState()
    val conversations by client.conversations.collectAsState()
    val connectionState by client.connectionState.collectAsState()
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var messageText by remember { mutableStateOf("") }
    var friendCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val events = remember { mutableStateListOf<String>() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val myCode = remember(client.userId, client.username) {
        if (client.userId != null && client.username != null)
            FriendCode.encode(client.userId!!, client.username!!) else ""
    }

    LaunchedEffect(Unit) {
        if (client.connectionState.value != ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) { try { client.connect() } catch (_: Exception) {} }
        }
    }

    LaunchedEffect(Unit) {
        client.events.collect { msg ->
            events.add(0, "${msg.type}: ${msg.text.take(50).ifEmpty { msg.username }}")
            if (events.size > 20) events.removeAt(events.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${client.username} | $connectionState", style = MaterialTheme.typography.labelMedium)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = {
            clipboardManager.setText(AnnotatedString(myCode))
            Toast.makeText(context, "Friend code copied!", Toast.LENGTH_SHORT).show()
        }) { Text("Copy my friend code") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = friendCode, onValueChange = { friendCode = it },
                label = { Text("Paste friend code") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                scope.launch {
                    try {
                        val decoded = FriendCode.decode(friendCode)
                        withContext(Dispatchers.IO) { client.befriend(decoded.userId, decoded.username) }
                        friendCode = ""
                        status = "Request sent to ${decoded.username}"
                    } catch (e: Exception) { status = "Error: ${e.message}" }
                }
            }) { Text("Add") }
        }

        val sent = friends.filter { it.status == FriendStatus.PENDING_SENT }
        sent.forEach { Text("${it.username} (pending)", color = MaterialTheme.colorScheme.outline) }

        pending.forEach { req ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(req.username)
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { client.acceptFriend(req.userId, req.username) }
                    }
                }) { Text("Accept") }
            }
        }

        val accepted = friends.filter { it.status == FriendStatus.ACCEPTED }
        Text("Friends (${accepted.size}):", style = MaterialTheme.typography.labelLarge)
        accepted.forEach { f ->
            TextButton(onClick = { selectedFriend = f.username }) {
                Text(if (f.username == selectedFriend) "> ${f.username}" else f.username)
            }
        }

        selectedFriend?.let { name ->
            Divider()
            val msgs = conversations[name] ?: emptyList()
            LazyColumn(Modifier.weight(1f)) {
                items(msgs) { m -> Text("${m.authorDeviceId.take(8)}: ${m.content}", style = MaterialTheme.typography.bodySmall) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = messageText, onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f), singleLine = true, label = { Text("Message") })
                Button(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.send(name, messageText) }
                            messageText = ""
                        } catch (e: Exception) { status = "Error: ${e.message}" }
                    }
                }) { Text("Send") }
            }
        }

        Divider()
        Text("Events:", style = MaterialTheme.typography.labelSmall)
        events.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { client.logout() }
                app.clearSession()
            }
        }) { Text("Logout") }
    }
}
