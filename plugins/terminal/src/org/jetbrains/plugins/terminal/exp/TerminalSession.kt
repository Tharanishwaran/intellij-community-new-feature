// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.exp

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.Key
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.terminal.TerminalColorPalette
import com.intellij.terminal.TerminalExecutorServiceManagerImpl
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.*
import com.jediterm.terminal.model.*
import org.jetbrains.plugins.terminal.TerminalUtil
import org.jetbrains.plugins.terminal.util.ShellIntegration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class TerminalSession(settings: JBTerminalSystemSettingsProviderBase,
                      val colorPalette: TerminalColorPalette,
                      val shellIntegration: ShellIntegration?) : Disposable {
  val model: TerminalModel
  internal val terminalStarterFuture: CompletableFuture<TerminalStarter?> = CompletableFuture()

  private val executorServiceManager: TerminalExecutorServiceManager = TerminalExecutorServiceManagerImpl()

  private val textBuffer: TerminalTextBuffer
  internal val controller: JediTerminal
  internal val commandManager: ShellCommandManager
  private val typeAheadManager: TerminalTypeAheadManager
  private val terminationListeners: MutableList<Runnable> = CopyOnWriteArrayList()

  init {
    val styleState = StyleState()
    val defaultStyle = TextStyle(TerminalColor { colorPalette.defaultForeground },
                                 TerminalColor { colorPalette.defaultBackground })
    styleState.setDefaultStyle(defaultStyle)
    textBuffer = TerminalTextBuffer(80, 24, styleState, AdvancedSettings.getInt("terminal.buffer.max.lines.count"), null)
    model = TerminalModel(textBuffer)
    controller = JediTerminal(ModelUpdatingTerminalDisplay(model, settings), textBuffer, styleState)

    commandManager = ShellCommandManager(controller)

    val typeAheadTerminalModel = JediTermTypeAheadModel(controller, textBuffer, settings)
    typeAheadManager = TerminalTypeAheadManager(typeAheadTerminalModel)
    val typeAheadDebouncer = JediTermDebouncerImpl(typeAheadManager::debounce, TerminalTypeAheadManager.MAX_TERMINAL_DELAY, executorServiceManager)
    typeAheadManager.setClearPredictionsDebouncer(typeAheadDebouncer)
  }

  fun start(ttyConnector: TtyConnector) {
    val terminalStarter = TerminalStarter(controller, ttyConnector, TtyBasedArrayDataStream(ttyConnector),
                                          typeAheadManager, executorServiceManager)
    terminalStarterFuture.complete(terminalStarter)
    executorServiceManager.unboundedExecutorService.submit {
      terminalStarter.start()
      try {
        ttyConnector.close()
      }
      catch (ignored: Exception) {
      }
      for (terminationListener in terminationListeners) {
        terminationListener.run()
      }
    }
  }

  fun addTerminationCallback(onTerminated: Runnable, parentDisposable: Disposable) {
    TerminalUtil.addItem(terminationListeners, onTerminated, parentDisposable)
  }

  fun sendCommandToExecute(shellCommand: String) {
    // Simulate pressing Ctrl+U in the terminal to clear all typings in the prompt
    val fullCommand = "\u0015" + shellCommand
    terminalStarterFuture.thenAccept {
      if (it != null) {
        TerminalUtil.sendCommandToExecute(fullCommand, it)
      }
    }
  }

  fun postResize(newSize: TermSize) {
    terminalStarterFuture.thenAccept {
      if (it != null && (newSize.columns != model.width || newSize.rows != model.height)) {
        typeAheadManager.onResize()
        it.postResize(newSize, RequestOrigin.User)
      }
    }
  }

  fun addCommandListener(listener: ShellCommandListener, parentDisposable: Disposable? = null) {
    commandManager.addListener(listener, parentDisposable)
  }

  override fun dispose() {
    executorServiceManager.shutdownWhenAllExecuted()
    // Complete to avoid memory leaks with hanging callbacks. If already completed, nothing will change.
    terminalStarterFuture.complete(null)
    terminalStarterFuture.getNow(null)?.close()
  }

  companion object {
    val KEY: Key<TerminalSession> = Key.create("TerminalSession")
    val DATA_KEY: DataKey<TerminalSession> = DataKey.create("TerminalSession")
  }
}