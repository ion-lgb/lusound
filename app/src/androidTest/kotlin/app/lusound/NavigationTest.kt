package app.lusound

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigateSettingsAndCreatePlaylist() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_import").assertIsDisplayed()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("tab_PLAYLISTS").performScrollTo().performClick()
        compose.onNodeWithTag("create_playlist").performClick()
        compose.onNodeWithTag("playlist_name").performTextInput("Smoke playlist")
        compose.onNodeWithTag("save_playlist").performClick()
        compose.onNodeWithTag("playlist_name").assertDoesNotExist()
        compose.onNodeWithTag("lusound_root").assertIsDisplayed()
    }
}
