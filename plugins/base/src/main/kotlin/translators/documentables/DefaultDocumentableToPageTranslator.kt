package org.jetbrains.dokka.base.translators.documentables

import org.jetbrains.dokka.base.signatures.SignatureProvider
import org.jetbrains.dokka.base.transformers.pages.comments.CommentsToContentConverter
import org.jetbrains.dokka.model.DProject
import org.jetbrains.dokka.pages.ProjectPageNode
import org.jetbrains.dokka.transformers.documentation.DocumentableToPageTranslator
import org.jetbrains.dokka.utilities.DokkaLogger

class DefaultDocumentableToPageTranslator(
    private val commentsToContentConverter: CommentsToContentConverter,
    private val signatureProvider: SignatureProvider,
    private val logger: DokkaLogger
) : DocumentableToPageTranslator {
    override fun invoke(project: DProject): ProjectPageNode =
        DefaultPageCreator(commentsToContentConverter, signatureProvider, logger).pageForProject(project)
}