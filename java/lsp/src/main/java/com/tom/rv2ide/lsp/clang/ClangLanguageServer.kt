/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.clang

import android.content.Context
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.IWorkspace
import java.nio.file.Path

/**
 * Placeholder Clang language server. The full clangd integration is planned for a
 * future release; this stub keeps the IDE compiling and registers a no-op server
 * so that opening C/C++ files does not crash. It returns empty completion,
 * reference and diagnostic results.
 */
class ClangLanguageServer(@Suppress("UNUSED_PARAMETER") context: Context) : ILanguageServer {

  companion object {
    const val SERVER_ID = "clang"
  }

  private var languageClient: ILanguageClient? = null

  override val serverId: String = SERVER_ID

  override val client: ILanguageClient?
    get() = languageClient

  override fun shutdown() {
    languageClient = null
  }

  override fun connectClient(client: ILanguageClient?) {
    languageClient = client
  }

  override fun applySettings(settings: IServerSettings?) = Unit

  override fun setupWorkspace(workspace: IWorkspace) = Unit

  override fun complete(params: CompletionParams?): CompletionResult = CompletionResult.EMPTY

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult = ReferenceResult(emptyList())

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult = DefinitionResult(emptyList())

  override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
    SignatureHelp(emptyList(), 0, 0)

  override suspend fun analyze(file: Path): DiagnosticResult = DiagnosticResult.NO_UPDATE
}
