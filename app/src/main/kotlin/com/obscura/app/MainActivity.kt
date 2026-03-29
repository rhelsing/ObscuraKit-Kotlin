package com.obscura.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
                val currentUsername by app.currentUsername.collectAsState()
                val client = app.client

                if (client != null && client.authState.collectAsState().value == AuthState.AUTHENTICATED) {
                    val friendList by client.friendList.collectAsState()
                    val pendingRequests by client.pendingRequests.collectAsState()
                    ConnectedScreen(client, app, friendList, pendingRequests)
                } else {
                    RegisterScreen(app)
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(app: ObscuraApp) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Ready") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = statusText, style = MaterialTheme.typography.bodySmall)
        Divider()
        Text("Register / Login", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (12+ chars)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (username.isBlank()) return@Button
                scope.launch {
                    try {
                        statusText = "Registering '$username'..."
                        withContext(Dispatchers.IO) {
                            app.openClientForUser(username)
                            app.client!!.register(username, password)
                        }
                    } catch (e: CancellationException) {
                    } catch (e: Exception) {
                        statusText = "Error: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }) { Text("Register") }

            Button(onClick = {
                if (username.isBlank()) return@Button
                scope.launch {
                    try {
                        statusText = "Logging in..."
                        withContext(Dispatchers.IO) {
                            app.openClientForUser(username)
                            app.client!!.login(username, password)
                        }
                    } catch (e: CancellationException) {
                    } catch (e: Exception) {
                        statusText = "Error: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }) { Text("Login") }
        }
    }
}

@Composable
fun ConnectedScreen(
    client: ObscuraClient,
    app: ObscuraApp,
    friends: List<com.obscura.kit.stores.FriendData>,
    pending: List<com.obscura.kit.stores.FriendData>
) {
    val scope = rememberCoroutineScope()
    var targetUserId by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("") }
    val conversations by client.conversations.collectAsState()
    val connectionState by client.connectionState.collectAsState()
    val events = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        if (client.connectionState.value != ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) {
                try { client.connect() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        client.events.collect { msg ->
            events.add(0, "${msg.type}: ${msg.text.take(50).ifEmpty { msg.username }}")
            if (events.size > 20) events.removeAt(events.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Auth: AUTHENTICATED | WS: $connectionState", style = MaterialTheme.typography.labelMedium)
        if (statusText.isNotEmpty()) Text(statusText, style = MaterialTheme.typography.bodySmall)

        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val userId = client.userId ?: ""
        @OptIn(ExperimentalFoundationApi::class)
        Text(
            "User: ${client.username} ($userId)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(userId))
                    Toast.makeText(context, "userId copied", Toast.LENGTH_SHORT).show()
                }
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = targetUserId,
                onValueChange = { targetUserId = it },
                label = { Text("Friend userId") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.befriend(targetUserId, "friend") }
                        targetUserId = ""
                        statusText = "Friend request sent!"
                    } catch (e: Exception) { statusText = "Error: ${e.message}" }
                }
            }) { Text("Add") }
        }

        if (pending.isNotEmpty()) {
            Text("Pending (${pending.size}):", style = MaterialTheme.typography.labelLarge)
            pending.forEach { req ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(req.username)
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { client.acceptFriend(req.userId, req.username) }
                            statusText = "Accepted ${req.username}"
                        }
                    }) { Text("Accept") }
                }
            }
        }

        Text("Friends (${friends.size}):", style = MaterialTheme.typography.labelLarge)
        friends.forEach { friend ->
            TextButton(onClick = { selectedFriend = friend.username }) {
                Text(if (friend.username == selectedFriend) "> ${friend.username}" else friend.username)
            }
        }

        selectedFriend?.let { friendName ->
            Divider()
            Text("Chat: $friendName", style = MaterialTheme.typography.titleSmall)
            val msgs = conversations[friendName] ?: emptyList()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(msgs) { msg ->
                    Text("${msg.authorDeviceId.take(8)}: ${msg.content}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Message") }
                )
                Button(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.send(friendName, messageText) }
                            messageText = ""
                        } catch (e: Exception) { statusText = "Error: ${e.message}" }
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
