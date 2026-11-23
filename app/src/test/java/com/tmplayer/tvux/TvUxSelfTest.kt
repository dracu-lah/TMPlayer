package com.tmplayer.tvux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import com.tmplayer.ui.components.dropStaleArrowRepeats
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The checklist's own rules fail on the case each exists for (WP-16): a button at TV Material's
 * real 1.1 scale fails [TvUxChecklist.focusWalk]'s size rule through the drawn rect, and its clip
 * rule in a clipped list; a yellow button that stays yellow fails the contrast rule; a 12 sp text
 * fails the text floor. A button drawn the way the design system draws one passes all of them, so
 * the rules do not fail everything either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvUxSelfTest {

    private companion object {
        val ARROWS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun show(padded: Boolean = true, content: @Composable () -> Unit): TvUxChecklist {
        FormFactor.override(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF101318)).then(if (padded) Modifier.padding(horizontal = 96.dp, vertical = 60.dp) else Modifier)) { content() }
        }
        return TvUxChecklist(rule, "self").apply {
            settle()
            windowForRemote()
        }
    }

    /** A 200 x 60 dp button: [scale] under focus, [rest] and [focusedFill] as its fills, a ring inside when [ring]. */
    @Composable
    private fun Button(
        name: String,
        scale: Float = 1f,
        rest: Color = Color(0xFF2A2F38),
        focusedFill: Color = Color.White,
        ring: Boolean = false,
        selected: Boolean = false,
        modifier: Modifier = Modifier,
        onClick: (() -> Unit)? = null,
    ) {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier
                .size(200.dp, 60.dp)
                .graphicsLayer { scaleX = if (focused) scale else 1f; scaleY = scaleX }
                .background(if (focused) focusedFill else rest, shape)
                .then(if (ring && focused) Modifier.border(3.dp, Color(0xFF2AABEE), shape) else Modifier)
                .semantics { contentDescription = name; role = Role.Button; if (selected) this.selected = true }
                .then(
                    if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                    else Modifier.focusable(interactionSource = source),
                ),
        )
    }

    private fun TvUxChecklist.rules() = findings.map { it.rule to it.node }

    @Test
    fun `a button at TV Material's 1_1 scale fails focusSizeStable through the drawn rect`() {
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Button("Grows", scale = 1.1f)
                Button("Neighbour")
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings}", "focusSizeStable" to "Grows" in ux.rules())
        // Its layout did not change: only the drawn rect says it grew.
        assertTrue(ux.findings.filter { it.node == "Grows" && it.rule == "focusSizeStable" }.all { it.detail.startsWith("drawn") })
    }

    @Test
    fun `tightly stacked full width buttons at a real 1_1 scale fail focusSizeStable`() {
        // The neighbour's margin reaches the node here, so the check reads against a neutral frame:
        // the growth into the 8 dp gap and over the neighbour must not be taken for its fill swap.
        val ux = show(padded = false) {
            Column(Modifier.padding(vertical = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Top", scale = 1.1f, modifier = Modifier.fillMaxWidth())
                Button("Middle", scale = 1.1f, modifier = Modifier.fillMaxWidth())
                Button("Bottom", scale = 1.1f, modifier = Modifier.fillMaxWidth())
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings} skipped ${ux.skipped}", ux.findings.any { it.rule == "focusSizeStable" && it.node == "Middle" })
    }

    @Test
    fun `tightly stacked full width buttons that only swap their fill pass focusSizeStable`() {
        val ux = show(padded = false) {
            Column(Modifier.padding(vertical = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Top", modifier = Modifier.fillMaxWidth())
                Button("Middle", modifier = Modifier.fillMaxWidth())
                Button("Bottom", modifier = Modifier.fillMaxWidth())
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings} skipped ${ux.skipped}", ux.findings.none { it.rule == "focusSizeStable" || it.rule == "drawnInsideClip" })
    }

    @Test
    fun `a pair alone on the screen at a real 1_1 scale fails and one that only swaps its fill passes`() {
        fun pair(scale: Float) = show(padded = false) {
            Column(Modifier.padding(vertical = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("First", scale = scale, modifier = Modifier.fillMaxWidth())
                Button("Second", scale = scale, modifier = Modifier.fillMaxWidth())
            }
        }
        val grows = pair(1.1f)
        grows.focusWalk()
        assertTrue("findings: ${grows.findings} skipped ${grows.skipped}", grows.findings.any { it.rule == "focusSizeStable" && it.node == "First" })
    }

    @Test
    fun `a pair alone on the screen that only swaps its fill passes focusSizeStable`() {
        val ux = show(padded = false) {
            Column(Modifier.padding(vertical = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("First", modifier = Modifier.fillMaxWidth())
                Button("Second", modifier = Modifier.fillMaxWidth())
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings} skipped ${ux.skipped}", ux.findings.none { it.rule == "focusSizeStable" || it.rule == "drawnInsideClip" })
    }

    @Test
    fun `a scaled button in a clipped list fails drawnInsideClip`() {
        val ux = show {
            Column {
                LazyRow(Modifier.width(660.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(4) { Button("Tile $it", scale = 1.1f) }
                }
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings} skipped ${ux.skipped}", ux.findings.any { it.rule == "drawnInsideClip" && it.node == "Tile 0" })
    }

    @Test
    fun `a yellow button that stays yellow fails focusContrast`() {
        val yellow = Color(0xFFFFD233)
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button("Coffee", rest = yellow, focusedFill = yellow)
                Button("Other")
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings}", "focusContrast" to "Coffee" in ux.rules())
    }

    @Test
    fun `a 12 sp text fails textFloor and a 14 sp one passes`() {
        val ux = show {
            Column {
                androidx.compose.foundation.text.BasicText("Small print", style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White))
                androidx.compose.foundation.text.BasicText("Caption floor", style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White))
            }
        }
        ux.textFloor(SizePass("Default,1.0", 1f, 1f))
        assertEquals(listOf("textFloor@Default,1.0" to "Small print"), ux.rules())
    }

    @Test
    fun `a button drawn as the design system draws it passes the focus rules`() {
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button("Play", ring = true)
                Button("Details", ring = true)
            }
        }
        ux.focusWalk()
        ux.labelled()
        ux.targets()
        assertEquals(emptyList<Finding>(), ux.findings)
    }

    @Test
    fun `an unnamed box without a role fails labelled and a 40 dp one fails targets`() {
        val ux = show {
            Box(Modifier.size(120.dp, 40.dp).background(Color.Gray).focusable())
        }
        ux.labelled()
        ux.targets()
        val rules = ux.findings.map { it.rule }
        assertEquals(listOf("labelled", "labelled", "targets"), rules)
    }

    @Test
    fun `allow lines match by glob and skip comments`() {
        val line = TvUxAllow.parse("settings:* | textFloor@* | * | WP-04 type scale # note", "WP-04.txt")!!
        assertTrue(line.allows(Finding("settings:Playback", "textFloor@Smaller,1.0", "Speed", "")))
        assertTrue(!line.allows(Finding("home", "textFloor@Smaller,1.0", "Speed", "")))
        assertNull(TvUxAllow.parse("# only a comment", "x"))
        assertNull(TvUxAllow.parse("home | targets", "x"))
    }

    @Test
    fun `the key table is read by key and column`() {
        val table = TvKeyTable.parse(
            """
            | Key | Bar | Tile, chat row, picker tile |
            | --- | --- | --- |
            | Menu | account menu (WP-02) | item options (today) |
            | Info | nothing | item options (today) |
            """.trimIndent(),
        )!!
        assertEquals("nothing", table.cell("Info", "Bar"))
        assertEquals("item options (today)", table.cell("Menu", "Tile, chat row, picker tile"))
        assertNull(table.cell("Search", "Bar"))
    }

    @Test
    fun `a screen that parks the remote on a hidden target fails entryFocus and one on a control passes`() {
        val hidden = FocusRequester()
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button("One")
                Button("Two")
                // A focus target with nothing to see or say: the remote is on it, and no control shows that.
                Box(Modifier.size(10.dp).focusRequester(hidden).focusTarget())
            }
        }
        if (ux.focused().isEmpty()) ux.focus(ux.focusables().first())
        ux.entryFocus()
        assertEquals(emptyList<Pair<String, String>>(), ux.rules())
        // The seeded fault: the screen's own request puts the remote on the hidden target.
        rule.runOnUiThread { hidden.requestFocus() }
        ux.settle()
        ux.entryFocus()
        assertEquals(listOf("entryFocus" to "screen"), ux.rules())
    }

    @Test
    fun `a control in the overscan band fails safeArea and one inside passes`() {
        val ux = show(padded = false) {
            Box(Modifier.fillMaxSize()) {
                Button("Edge", modifier = Modifier.padding(start = 10.dp, top = 100.dp))
                Button("Inside", modifier = Modifier.padding(start = 300.dp, top = 100.dp))
            }
        }
        ux.safeArea()
        assertEquals(listOf("safeArea" to "Edge"), ux.rules())
    }

    @Test
    fun `a control that eats every arrow fails noTrap`() {
        val trap = FocusRequester()
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button("Trap", modifier = Modifier.focusRequester(trap).onPreviewKeyEvent { it.key in ARROWS })
                Button("Free")
            }
        }
        trap.requestFocus()
        ux.settle()
        ux.arrowGraph()
        assertTrue("findings: ${ux.findings}", "noTrap" to "Trap" in ux.rules())
    }

    @Test
    fun `an action five presses away fails reach and one within three passes`() {
        val first = FocusRequester()
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Start", modifier = Modifier.width(100.dp).focusRequester(first))
                for (n in 1..4) Button("Step $n", modifier = Modifier.width(100.dp))
                Button("Far", modifier = Modifier.width(100.dp))
            }
        }
        first.requestFocus()
        ux.settle()
        val graph = ux.arrowGraph()
        ux.reach(graph, "Step 2")
        ux.reach(graph, "Far")
        ux.reach(graph, "Missing")
        assertEquals(listOf("reach" to "Far", "reach" to "Missing"), ux.rules())
    }

    @Test
    fun `a list that drops stale repeats passes heldDown`() {
        val first = FocusRequester()
        val ux = show {
            LazyColumn(Modifier.dropStaleArrowRepeats(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(20) { n -> Button("Row $n", modifier = if (n == 0) Modifier.focusRequester(first) else Modifier) }
            }
        }
        first.requestFocus()
        ux.settle()
        ux.heldDown()
        assertEquals(emptyList<Pair<String, String>>(), ux.rules())
    }

    @Test
    fun `a list that acts on stale repeats fails heldDown`() {
        val first = FocusRequester()
        val ux = show {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(20) { n -> Button("Row $n", modifier = if (n == 0) Modifier.focusRequester(first) else Modifier) }
            }
        }
        first.requestFocus()
        ux.settle()
        ux.heldDown()
        assertEquals(listOf("heldDown" to "screen"), ux.rules())
    }

    @Test
    fun `a selected tab that looks the same focused fails selectedNotFocused`() {
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button("Open tab", rest = Color.White, focusedFill = Color.White, selected = true)
                Button("Other tab", ring = true)
            }
        }
        ux.focusWalk()
        assertTrue("findings: ${ux.findings}", "selectedNotFocused" to "Open tab" in ux.rules())
    }

    @Test
    fun `a key the table says does nothing but moves the remote fails keyOwnership`() {
        val first = FocusRequester()
        val other = FocusRequester()
        val table = TvKeyTable.parse(
            """
            | Key | Bar |
            | --- | --- |
            | Info | nothing |
            | Search | nothing |
            """.trimIndent(),
        )
        val ux = show {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Button(
                    "Bar item",
                    modifier = Modifier.focusRequester(first).onKeyEvent {
                        if (it.key == Key.Info && it.type == KeyEventType.KeyDown) { other.requestFocus(); true } else false
                    },
                )
                Button("Elsewhere", modifier = Modifier.focusRequester(other))
            }
        }
        first.requestFocus()
        ux.settle()
        ux.keyOwnership("Bar", table)
        assertEquals(listOf("keyOwnership" to "Info"), ux.rules())
    }

    @Test
    fun `Back that drops the remote somewhere else fails backReturns and one that returns passes`() {
        var open by mutableStateOf<String?>(null)
        val openers = mapOf("Opens" to FocusRequester(), "Returns" to FocusRequester())
        val other = FocusRequester()
        val ux = show {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    for ((name, req) in openers) Button(name, ring = true, modifier = Modifier.focusRequester(req), onClick = { open = name })
                    Button("Other", ring = true, modifier = Modifier.focusRequester(other))
                }
                open?.let { which ->
                    val sheet = remember { FocusRequester() }
                    BackHandler {
                        open = null
                        // The seeded fault: "Opens" sends the remote to Other instead of back to it.
                        (if (which == "Opens") other else openers.getValue(which)).requestFocus()
                    }
                    Button("Sheet", ring = true, modifier = Modifier.focusRequester(sheet))
                    LaunchedEffect(Unit) { sheet.requestFocus() }
                }
            }
        }
        ux.backReturns("Opens")
        ux.backReturns("Returns")
        assertEquals(listOf("backReturns" to "Opens"), ux.rules())
    }
}
