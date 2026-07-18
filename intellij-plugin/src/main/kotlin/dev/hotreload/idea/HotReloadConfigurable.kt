package dev.hotreload.idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Structured project settings. Discovery values are editable combo boxes: a project that cannot
 * be inspected (or an intentionally unusual build) never blocks an advanced user from typing an
 * explicit value. Text areas use one token per line, avoiding ambiguous shell splitting.
 */
class HotReloadConfigurable(private val project: Project) : Configurable {
    private val state get() = HotReloadSettings.getInstance(project).state
    private var panel: JPanel? = null
    private val cli = JBTextField()
    private val projectDir = JBTextField()
    private val profile = editableCombo()
    private val appModule = editableCombo()
    private val appId = editableCombo()
    private val variant = editableCombo()
    private val modules = editableCombo()
    private val targetJdk = JBTextField()
    private val device = JBTextField()
    private val sdk = JBTextField()
    private val literals = JCheckBox("Enable live-literal fast path")
    private val zeroTouch = JCheckBox("Use zero-touch bootstrap")
    private val skipPreflight = JCheckBox("Skip environment preflight (advanced)")
    private val gradleArgs = JBTextArea(3, 44)
    private val advancedTokens = JBTextArea(3, 44)
    private val resolved = JBTextArea(3, 44).apply { isEditable = false; lineWrap = true; wrapStyleWord = false }
    private val discoveryStatus = JLabel("Refresh to discover app modules and debuggable variants.")
    private var discovery: DiscoveryChoices? = null
    private var loading = false
    private var updatingDiscoveryControls = false

    override fun getDisplayName(): String = "Compose Hot Reload"

    override fun createComponent(): JComponent {
        if (panel != null) return panel!!
        val form = JPanel(GridBagLayout())
        var row = 0
        fun add(label: String, component: JComponent, comment: String? = null) {
            val c = GridBagConstraints().apply {
                gridy = row; insets = Insets(4, 6, 4, 6); anchor = GridBagConstraints.NORTHWEST
            }
            c.gridx = 0; c.weightx = 0.0; form.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
            form.add(component, c)
            if (comment != null) {
                c.gridx = 1; c.gridy = row + 1; c.weightx = 1.0
                form.add(JLabel("<html><small>$comment</small></html>"), c)
                row++
            }
            row++
        }
        add("CLI launcher:", cli, "Blank uses the CLI bundled with this plugin.")
        add("Project dir:", projectDir, "Blank uses this IDE project's base path.")
        val refresh = JButton("Refresh discovery").apply { addActionListener { refreshDiscovery() } }
        add("Discovery:", refresh)
        add("", discoveryStatus)
        add("Profile:", profile, "Optional `hotreload configure` profile; explicit fields below override it.")
        add("App module:", appModule, "Choose a discovered Android app module or type a Gradle path.")
        add("Variant:", variant, "Only debuggable discovered variants are offered; type a custom variant if needed.")
        add("Application id:", appId)
        add("Watched modules:", modules, "Comma-separated module specs. Refresh proposes the discovered dependency closure.")
        add("Target project JDK:", targetJdk, "Passed as `--project-java-home`; blank uses the CLI JVM.")
        add("Device serial:", device, "Optional adb serial for more than one device.")
        add("SDK path:", sdk, "Blank uses ANDROID_HOME.")
        add("Options:", JPanel().apply { add(literals); add(zeroTouch); add(skipPreflight) })
        add("Gradle args:", JBScrollPane(gradleArgs), "One exact argument per line; each becomes `--gradle-arg`.")
        add("Advanced raw overrides:", JBScrollPane(advancedTokens), "Append-only escape hatch: one exact CLI token per line (never shell-split).")
        add("Resolved command:", JBScrollPane(resolved), "Read-only preview of the exact token command Start will execute.")
        panel = JPanel(BorderLayout()).apply { add(JBScrollPane(form), BorderLayout.CENTER) }
        installPreviewListeners()
        reset()
        return panel!!
    }

    override fun isModified(): Boolean =
        config() != HotReloadWatchConfig.from(state, project.basePath.orEmpty()) || skipPreflight.isSelected != state.skipPreflight

    override fun apply() {
        val value = config()
        state.cliLauncherPath = value.cliLauncherPath
        state.projectDir = value.projectDir.takeUnless { it == project.basePath.orEmpty() }.orEmpty()
        state.profile = value.profile
        state.appModule = value.appModule
        state.appId = value.appId
        state.variant = value.variant
        state.targetJdk = value.targetJdk
        state.modules = value.watchedModules
        state.device = value.device
        state.sdkPath = value.sdkPath
        state.literals = value.literals
        state.zeroTouch = value.zeroTouch
        state.gradleArgs = value.gradleArgs.toMutableList()
        state.advancedTokens = value.advancedTokens.toMutableList()
        state.skipPreflight = skipPreflight.isSelected
        // New settings are token based. Clear this field after its one-time legacy migration.
        state.extraArgs = ""
    }

    override fun reset() {
        val value = HotReloadWatchConfig.from(state, project.basePath.orEmpty())
        cli.text = value.cliLauncherPath; projectDir.text = state.projectDir
        setCombo(profile, value.profile); setCombo(appModule, value.appModule); setCombo(appId, value.appId)
        setCombo(variant, value.variant); setCombo(modules, value.watchedModules)
        targetJdk.text = value.targetJdk; device.text = value.device; sdk.text = value.sdkPath
        literals.isSelected = value.literals; zeroTouch.isSelected = value.zeroTouch
        skipPreflight.isSelected = state.skipPreflight
        gradleArgs.text = value.gradleArgs.joinToString("\n")
        advancedTokens.text = value.advancedTokens.joinToString("\n")
        updatePreview()
    }

    override fun disposeUIResources() { panel = null }

    private fun refreshDiscovery() {
        loading = true
        discoveryStatus.text = "Discovering (Gradle runs outside the IDE UI thread)…"
        HotReloadDiscoveryService.getInstance(project).refresh(config(), ModalityState.current()) { result ->
            loading = false
            result.onSuccess {
                discovery = it
                updatingDiscoveryControls = true
                replaceChoices(appModule, it.appModules)
                updatingDiscoveryControls = false
                updateVariantsAndDefaults()
                discoveryStatus.text = "Discovered ${it.appModules.size} app module(s)."
            }.onFailure { error ->
                discoveryStatus.text = "Discovery failed: ${error.message ?: error.javaClass.simpleName}"
            }
            updatePreview()
        }
    }

    private fun updateVariantsAndDefaults() {
        if (updatingDiscoveryControls) return
        updatingDiscoveryControls = true
        try {
        val values = discovery ?: return
        val app = comboText(appModule)
        replaceChoices(variant, values.variants(app))
        val selectedVariant = comboText(variant)
        values.appId(app, selectedVariant)?.let { replaceChoices(appId, listOf(it)) }
        val closure = values.moduleClosure(app, selectedVariant)
        if (closure.isNotEmpty()) {
            replaceChoices(modules, listOf(closure.joinToString(",")))
        }
        } finally {
            updatingDiscoveryControls = false
        }
    }

    private fun config(): HotReloadWatchConfig = HotReloadWatchConfig(
        cliLauncherPath = cli.text.trim(), projectDir = projectDir.text.trim().ifBlank { project.basePath.orEmpty() },
        profile = comboText(profile), appModule = comboText(appModule), appId = comboText(appId),
        variant = comboText(variant), watchedModules = comboText(modules), targetJdk = targetJdk.text.trim(),
        device = device.text.trim(), sdkPath = sdk.text.trim(), literals = literals.isSelected,
        zeroTouch = zeroTouch.isSelected, gradleArgs = tokenLines(gradleArgs), advancedTokens = tokenLines(advancedTokens),
    )

    private fun installPreviewListeners() {
        val update = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updatePreview()
            override fun removeUpdate(e: DocumentEvent) = updatePreview()
            override fun changedUpdate(e: DocumentEvent) = updatePreview()
        }
        listOf(cli, projectDir, targetJdk, device, sdk, gradleArgs, advancedTokens).forEach { it.document.addDocumentListener(update) }
        listOf(profile, appModule, appId, variant, modules).forEach { combo ->
            combo.addActionListener {
                if (!loading && !updatingDiscoveryControls && (combo === appModule || combo === variant)) updateVariantsAndDefaults()
                updatePreview()
            }
        }
        literals.addActionListener { updatePreview() }; zeroTouch.addActionListener { updatePreview() }
    }

    private fun updatePreview() {
        if (panel != null) resolved.text = HotReloadService.getInstance(project).renderedCommand(config())
    }

    private fun editableCombo(): JComboBox<String> = JComboBox<String>().apply { isEditable = true }
    private fun comboText(combo: JComboBox<String>): String = combo.editor.item?.toString()?.trim().orEmpty()
    private fun setCombo(combo: JComboBox<String>, value: String) { combo.editor.item = value }
    private fun replaceChoices(combo: JComboBox<String>, choices: List<String>) {
        val current = comboText(combo)
        combo.removeAllItems(); choices.forEach(combo::addItem)
        setCombo(combo, current.ifBlank { choices.firstOrNull().orEmpty() })
    }
    private fun tokenLines(area: JTextArea): List<String> = area.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
}
