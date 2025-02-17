package org.jetbrains.dokka.base.renderers.html

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import org.jetbrains.dokka.DokkaSourceSetID
import org.jetbrains.dokka.base.DokkaBase
import org.jetbrains.dokka.base.DokkaBaseConfiguration
import org.jetbrains.dokka.base.DokkaBaseConfiguration.Companion.defaultFooterMessage
import org.jetbrains.dokka.base.renderers.*
import org.jetbrains.dokka.base.renderers.html.command.consumers.ImmediateResolutionTagConsumer
import org.jetbrains.dokka.base.renderers.pageId
import org.jetbrains.dokka.base.resolvers.anchors.SymbolAnchorHint
import org.jetbrains.dokka.base.resolvers.local.DokkaBaseLocationProvider
import org.jetbrains.dokka.base.templating.*
import org.jetbrains.dokka.links.DRI
import org.jetbrains.dokka.model.DisplaySourceSet
import org.jetbrains.dokka.model.properties.PropertyContainer
import org.jetbrains.dokka.model.sourceSetIDs
import org.jetbrains.dokka.model.withDescendants
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.pages.HtmlContent
import org.jetbrains.dokka.plugability.*
import org.jetbrains.dokka.utilities.htmlEscape
import org.jetbrains.kotlin.utils.addIfNotNull
import java.net.URI

open class HtmlRenderer(
    context: DokkaContext
) : DefaultRenderer<FlowContent>(context) {
    private val configuration = configuration<DokkaBase, DokkaBaseConfiguration>(context)

    private val sourceSetDependencyMap: Map<DokkaSourceSetID, List<DokkaSourceSetID>> =
        context.configuration.sourceSets.map { sourceSet ->
            sourceSet.sourceSetID to context.configuration.sourceSets
                .map { it.sourceSetID }
                .filter { it in sourceSet.dependentSourceSets }
        }.toMap()

    private var shouldRenderSourceSetBubbles: Boolean = false

    override val preprocessors = context.plugin<DokkaBase>().query { htmlPreprocessors }

    private val tabSortingStrategy = context.plugin<DokkaBase>().querySingle { tabSortingStrategy }

    private fun <R> TagConsumer<R>.prepareForTemplates() =
        if (context.configuration.delayTemplateSubstitution || this is ImmediateResolutionTagConsumer) this
        else ImmediateResolutionTagConsumer(this, context)

    private fun <T : ContentNode> sortTabs(strategy: TabSortingStrategy, tabs: Collection<T>): List<T> {
        val sorted = strategy.sort(tabs)
        if (sorted.size != tabs.size)
            context.logger.warn("Tab sorting strategy has changed number of tabs from ${tabs.size} to ${sorted.size}")
        return sorted
    }

    override fun FlowContent.wrapGroup(
        node: ContentGroup,
        pageContext: ContentPage,
        childrenCallback: FlowContent.() -> Unit
    ) {
        val additionalClasses = node.style.joinToString(" ") { it.toString().toLowerCase() }
        return when {
            node.hasStyle(ContentStyle.TabbedContent) -> div(additionalClasses) {
                val secondLevel = node.children.filterIsInstance<ContentComposite>().flatMap { it.children }
                    .filterIsInstance<ContentHeader>().flatMap { it.children }.filterIsInstance<ContentText>()
                val firstLevel = node.children.filterIsInstance<ContentHeader>().flatMap { it.children }
                    .filterIsInstance<ContentText>()

                val renderable = sortTabs(tabSortingStrategy, firstLevel.union(secondLevel))

                div(classes = "tabs-section") {
                    attributes["tabs-section"] = "tabs-section"
                    renderable.forEachIndexed { index, node ->
                        button(classes = "section-tab") {
                            if (index == 0) attributes["data-active"] = ""
                            attributes["data-togglable"] = node.text
                            text(node.text)
                        }
                    }
                }
                div(classes = "tabs-section-body") {
                    childrenCallback()
                }
            }
            node.hasStyle(ContentStyle.WithExtraAttributes) -> div {
                node.extra.extraHtmlAttributes().forEach { attributes[it.extraKey] = it.extraValue }
                childrenCallback()
            }
            node.dci.kind in setOf(ContentKind.Symbol) -> div("symbol $additionalClasses") {
                childrenCallback()
                if (node.hasStyle(TextStyle.Monospace)) copyButton()
            }
            node.hasStyle(TextStyle.BreakableAfter) -> {
                span { childrenCallback() }
                wbr { }
            }
            node.hasStyle(TextStyle.Breakable) -> {
                span("breakable-word") { childrenCallback() }
            }
            node.hasStyle(TextStyle.Span) -> span { childrenCallback() }
            node.dci.kind == ContentKind.Symbol -> div("symbol $additionalClasses") { childrenCallback() }
            node.dci.kind == ContentKind.BriefComment -> div("brief $additionalClasses") { childrenCallback() }
            node.dci.kind == ContentKind.Cover -> div("cover $additionalClasses") { //TODO this can be removed
                childrenCallback()
            }
            node.hasStyle(TextStyle.Paragraph) -> p(additionalClasses) { childrenCallback() }
            node.hasStyle(TextStyle.Block) -> div(additionalClasses) { childrenCallback() }
            node.isAnchorable -> buildAnchor(
                node.anchor!!,
                node.anchorLabel!!,
                node.sourceSetsFilters
            ) { childrenCallback() }
            node.extra[InsertTemplateExtra] != null -> node.extra[InsertTemplateExtra]?.let { templateCommand(it.command) }
                ?: Unit
            else -> childrenCallback()
        }
    }

    private fun FlowContent.filterButtons(page: PageNode) {
        if (shouldRenderSourceSetBubbles && page is ContentPage) {
            div(classes = "filter-section") {
                id = "filter-section"
                page.content.withDescendants().flatMap { it.sourceSets }.distinct()
                    .sortedBy { it.comparableKey }.forEach {
                        button(classes = "platform-tag platform-selector") {
                            attributes["data-active"] = ""
                            attributes["data-filter"] = it.sourceSetIDs.merged.toString()
                            when (it.platform.key) {
                                "common" -> classes = classes + "common-like"
                                "native" -> classes = classes + "native-like"
                                "jvm" -> classes = classes + "jvm-like"
                                "js" -> classes = classes + "js-like"
                            }
                            text(it.name)
                        }
                    }
            }
        }
    }

    private fun FlowContent.copyButton() = span(classes = "top-right-position") {
        span("copy-icon")
        copiedPopup("Content copied to clipboard", "popup-to-left")
    }

    private fun FlowContent.copiedPopup(notificationContent: String, additionalClasses: String = "") =
        div("copy-popup-wrapper $additionalClasses") {
            span("copy-popup-icon")
            span {
                text(notificationContent)
            }
        }

    override fun FlowContent.buildPlatformDependent(
        content: PlatformHintedContent,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?
    ) =
        buildPlatformDependent(
            content.sourceSets.filter {
                sourceSetRestriction == null || it in sourceSetRestriction
            }.map { it to setOf(content.inner) }.toMap(),
            pageContext,
            content.extra,
            content.style
        )

    private fun FlowContent.buildPlatformDependent(
        nodes: Map<DisplaySourceSet, Collection<ContentNode>>,
        pageContext: ContentPage,
        extra: PropertyContainer<ContentNode> = PropertyContainer.empty(),
        styles: Set<Style> = emptySet(),
        shouldHaveTabs: Boolean = shouldRenderSourceSetBubbles
    ) {
        val contents = contentsForSourceSetDependent(nodes, pageContext)

        val divStyles = "platform-hinted ${styles.joinToString()}" + if (shouldHaveTabs) " with-platform-tabs" else ""
        div(divStyles) {
            attributes["data-platform-hinted"] = "data-platform-hinted"
            extra.extraHtmlAttributes().forEach { attributes[it.extraKey] = it.extraValue }
            if (shouldHaveTabs) {
                div("platform-bookmarks-row") {
                    attributes["data-toggle-list"] = "data-toggle-list"
                    contents.forEachIndexed { index, pair ->
                        button(classes = "platform-bookmark") {
                            attributes["data-filterable-current"] = pair.first.sourceSetIDs.merged.toString()
                            attributes["data-filterable-set"] = pair.first.sourceSetIDs.merged.toString()
                            if (index == 0) attributes["data-active"] = ""
                            attributes["data-toggle"] = pair.first.sourceSetIDs.merged.toString()
                            when (pair.first.platform.key) {
                                "common" -> classes = classes + "common-like"
                                "native" -> classes = classes + "native-like"
                                "jvm" -> classes = classes + "jvm-like"
                                "js" -> classes = classes + "js-like"
                            }
                            attributes["data-toggle"] = pair.first.sourceSetIDs.merged.toString()
                            text(pair.first.name)
                        }
                    }
                }
            }
            contents.forEach {
                consumer.onTagContentUnsafe { +it.second }
            }
        }
    }

    private fun contentsForSourceSetDependent(
        nodes: Map<DisplaySourceSet, Collection<ContentNode>>,
        pageContext: ContentPage,
    ): List<Pair<DisplaySourceSet, String>> {
        var counter = 0
        return nodes.toList().map { (sourceSet, elements) ->
            val htmlContent = createHTML(prettyPrint = false).prepareForTemplates().div {
                elements.forEach {
                    buildContentNode(it, pageContext, sourceSet.toSet())
                }
            }.stripDiv()
            sourceSet to createHTML(prettyPrint = false).prepareForTemplates()
                .div(classes = "content sourceset-depenent-content") {
                    if (counter++ == 0) attributes["data-active"] = ""
                    attributes["data-togglable"] = sourceSet.sourceSetIDs.merged.toString()
                    unsafe {
                        +htmlContent
                    }
                }
        }.sortedBy { it.first.comparableKey }
    }

    override fun FlowContent.buildDivergent(node: ContentDivergentGroup, pageContext: ContentPage) {
        fun groupDivergentInstancesWithSourceSet(
            instances: List<ContentDivergentInstance>,
            sourceSet: DisplaySourceSet,
            pageContext: ContentPage,
            beforeTransformer: (ContentDivergentInstance, ContentPage, DisplaySourceSet) -> String,
            afterTransformer: (ContentDivergentInstance, ContentPage, DisplaySourceSet) -> String
        ): Map<SerializedBeforeAndAfter, List<ContentDivergentInstance>> =
            instances.map { instance ->
                instance to Pair(
                    beforeTransformer(instance, pageContext, sourceSet),
                    afterTransformer(instance, pageContext, sourceSet)
                )
            }.groupBy(
                Pair<ContentDivergentInstance, SerializedBeforeAndAfter>::second,
                Pair<ContentDivergentInstance, SerializedBeforeAndAfter>::first
            )

        if (node.implicitlySourceSetHinted) {
            val groupedInstancesBySourceSet = node.children.flatMap { instance ->
                instance.sourceSets.map { sourceSet -> instance to sourceSet }
            }.groupBy(
                Pair<ContentDivergentInstance, DisplaySourceSet>::second,
                Pair<ContentDivergentInstance, DisplaySourceSet>::first
            )

            val nodes = groupedInstancesBySourceSet.mapValues {
                val distinct =
                    groupDivergentInstancesWithSourceSet(it.value, it.key, pageContext,
                        beforeTransformer = { instance, _, sourceSet ->
                            createHTML(prettyPrint = false).prepareForTemplates().div {
                                instance.before?.let { before ->
                                    buildContentNode(before, pageContext, sourceSet)
                                }
                            }.stripDiv()
                        },
                        afterTransformer = { instance, _, sourceSet ->
                            createHTML(prettyPrint = false).prepareForTemplates().div {
                                instance.after?.let { after ->
                                    buildContentNode(after, pageContext, sourceSet)
                                }
                            }.stripDiv()
                        })
                val contentOfSourceSet = mutableListOf<ContentNode>()
                distinct.onEachIndexed{ i, (_, distinctInstances) ->
                    contentOfSourceSet.addIfNotNull(distinctInstances.firstOrNull()?.before)
                    contentOfSourceSet.addAll(distinctInstances.map { it.divergent })
                    contentOfSourceSet.addIfNotNull(
                        distinctInstances.firstOrNull()?.after
                            ?: if (i != distinct.size - 1) ContentBreakLine(it.key) else null
                    )
                    if(node.dci.kind == ContentKind.Main && i != distinct.size - 1)
                        contentOfSourceSet.add(ContentBreakLine(it.key))
                }
                contentOfSourceSet
            }
            buildPlatformDependent(nodes, pageContext)
        } else {
            node.children.forEach {
                buildContentNode(it.divergent, pageContext, it.sourceSets)
            }
        }
    }

    override fun FlowContent.buildList(
        node: ContentList,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?
    ) = if (node.ordered) ol { buildListItems(node.children, pageContext, sourceSetRestriction) }
    else ul { buildListItems(node.children, pageContext, sourceSetRestriction) }

    open fun OL.buildListItems(
        items: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>? = null
    ) {
        items.forEach {
            if (it is ContentList)
                buildList(it, pageContext)
            else
                li { it.build(this, pageContext, sourceSetRestriction) }
        }
    }

    open fun UL.buildListItems(
        items: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>? = null
    ) {
        items.forEach {
            if (it is ContentList)
                buildList(it, pageContext)
            else
                li { it.build(this, pageContext) }
        }
    }

    override fun FlowContent.buildResource(
        node: ContentEmbeddedResource,
        pageContext: ContentPage
    ) = // TODO: extension point there
        if (node.isImage()) {
            //TODO: add imgAttrs parsing
            val imgAttrs = node.extra.allOfType<SimpleAttr>().joinAttr()
            img(src = node.address, alt = node.altText)
        } else {
            println("Unrecognized resource type: $node")
        }

    private fun FlowContent.buildRow(
        node: ContentGroup,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
        style: Set<Style>
    ) {
        node.children
            .filter { sourceSetRestriction == null || it.sourceSets.any { s -> s in sourceSetRestriction } }
            .takeIf { it.isNotEmpty() }
            ?.let {
                when (pageContext) {
                    is MultimoduleRootPage -> buildRowForMultiModule(node, it, pageContext, sourceSetRestriction, style)
                    is ModulePage -> buildRowForModule(node, it, pageContext, sourceSetRestriction, style)
                    else -> buildRowForContent(node, it, pageContext, sourceSetRestriction, style)
                }
            }
    }

    private fun FlowContent.buildRowForMultiModule(
        contextNode: ContentGroup,
        toRender: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
        style: Set<Style>
    ) {
        buildAnchor(contextNode)
        div(classes = "table-row") {
            div("main-subrow " + contextNode.style.joinToString(separator = " ")) {
                buildRowHeaderLink(toRender, pageContext, sourceSetRestriction, contextNode.anchor, "w-100")
                div {
                    buildRowBriefSectionForDocs(toRender, pageContext, sourceSetRestriction)
                }
            }
        }
    }

    private fun FlowContent.buildRowForModule(
        contextNode: ContentGroup,
        toRender: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
        style: Set<Style>
    ) {
        buildAnchor(contextNode)
        div(classes = "table-row") {
            addSourceSetFilteringAttributes(contextNode)
            div {
                div("main-subrow " + contextNode.style.joinToString(separator = " ")) {
                    buildRowHeaderLink(toRender, pageContext, sourceSetRestriction, contextNode.anchor)
                    div("pull-right") {
                        if (ContentKind.shouldBePlatformTagged(contextNode.dci.kind)) {
                            createPlatformTags(contextNode, cssClasses = "no-gutters")
                        }
                    }
                }
                div {
                    buildRowBriefSectionForDocs(toRender, pageContext, sourceSetRestriction)
                }
            }
        }
    }

    private fun FlowContent.buildRowForContent(
        contextNode: ContentGroup,
        toRender: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
        style: Set<Style>
    ) {
        buildAnchor(contextNode)
        div(classes = "table-row") {
            addSourceSetFilteringAttributes(contextNode)
            div("main-subrow keyValue " + contextNode.style.joinToString(separator = " ")) {
                buildRowHeaderLink(toRender, pageContext, sourceSetRestriction, contextNode.anchor)
                div {
                    toRender.filter { it !is ContentLink && !it.hasStyle(ContentStyle.RowTitle) }
                        .takeIf { it.isNotEmpty() }?.let {
                            div("title") {
                                it.forEach {
                                    it.build(this, pageContext, sourceSetRestriction)
                                }
                            }
                        }
                }
            }
        }
    }

    private fun FlowContent.buildRowHeaderLink(
        toRender: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
        anchorDestination: String?,
        classes: String = ""
    ) {
        toRender.filter { it is ContentLink || it.hasStyle(ContentStyle.RowTitle) }.takeIf { it.isNotEmpty() }?.let {
            div(classes) {
                it.filter { sourceSetRestriction == null || it.sourceSets.any { s -> s in sourceSetRestriction } }
                    .forEach {
                        span("inline-flex") {
                            it.build(this, pageContext, sourceSetRestriction)
                            if (it is ContentLink && !anchorDestination.isNullOrBlank()) buildAnchorCopyButton(
                                anchorDestination
                            )
                        }
                    }
            }
        }
    }

    private fun FlowContent.addSourceSetFilteringAttributes(
        contextNode: ContentGroup,
    ) {
        attributes["data-filterable-current"] = contextNode.sourceSets.joinToString(" ") {
            it.sourceSetIDs.merged.toString()
        }
        attributes["data-filterable-set"] = contextNode.sourceSets.joinToString(" ") {
            it.sourceSetIDs.merged.toString()
        }
    }

    private fun FlowContent.buildRowBriefSectionForDocs(
        toRender: List<ContentNode>,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?,
    ) {
        toRender.filter { it !is ContentLink }.takeIf { it.isNotEmpty() }?.let {
            it.forEach {
                span(classes = if (it.dci.kind == ContentKind.Comment) "brief-comment" else "") {
                    it.build(this, pageContext, sourceSetRestriction)
                }
            }
        }
    }

    private fun FlowContent.createPlatformTagBubbles(sourceSets: List<DisplaySourceSet>, cssClasses: String = "") {
        if (shouldRenderSourceSetBubbles) {
            div("platform-tags $cssClasses") {
                sourceSets.sortedBy { it.name }.forEach {
                    div("platform-tag") {
                        when (it.platform.key) {
                            "common" -> classes = classes + "common-like"
                            "native" -> classes = classes + "native-like"
                            "jvm" -> classes = classes + "jvm-like"
                            "js" -> classes = classes + "js-like"
                        }
                        text(it.name)
                    }
                }
            }
        }
    }

    private fun FlowContent.createPlatformTags(
        node: ContentNode,
        sourceSetRestriction: Set<DisplaySourceSet>? = null,
        cssClasses: String = ""
    ) {
        node.takeIf { sourceSetRestriction == null || it.sourceSets.any { s -> s in sourceSetRestriction } }?.let {
            createPlatformTagBubbles(node.sourceSets.filter {
                sourceSetRestriction == null || it in sourceSetRestriction
            }.sortedBy { it.name }, cssClasses)
        }
    }

    override fun FlowContent.buildTable(
        node: ContentTable,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?
    ) {
        when {
            node.style.contains(CommentTable) -> buildDefaultTable(node, pageContext, sourceSetRestriction)
            else -> div(classes = "table") {
                node.extra.extraHtmlAttributes().forEach { attributes[it.extraKey] = it.extraValue }
                node.children.forEach {
                    buildRow(it, pageContext, sourceSetRestriction, node.style)
                }
            }
        }

    }

    fun FlowContent.buildDefaultTable(
        node: ContentTable,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?
    ) {
        table {
            thead {
                node.header.forEach {
                    tr {
                        it.children.forEach {
                            th {
                                it.build(this@table, pageContext, sourceSetRestriction)
                            }
                        }
                    }
                }
            }
            tbody {
                node.children.forEach {
                    tr {
                        it.children.forEach {
                            td {
                                it.build(this, pageContext, sourceSetRestriction)
                            }
                        }
                    }
                }
            }
        }
    }


    override fun FlowContent.buildHeader(level: Int, node: ContentHeader, content: FlowContent.() -> Unit) {
        val classes = node.style.joinToString { it.toString() }.toLowerCase()
        when (level) {
            1 -> h1(classes = classes, content)
            2 -> h2(classes = classes, content)
            3 -> h3(classes = classes, content)
            4 -> h4(classes = classes, content)
            5 -> h5(classes = classes, content)
            else -> h6(classes = classes, content)
        }
    }

    private fun FlowContent.buildAnchor(
        anchor: String,
        anchorLabel: String,
        sourceSets: String,
        content: FlowContent.() -> Unit
    ) {
        a {
            attributes["data-name"] = anchor
            attributes["anchor-label"] = anchorLabel
            attributes["id"] = anchor
            attributes["data-filterable-set"] = sourceSets
        }
        content()
    }

    private fun FlowContent.buildAnchor(anchor: String, anchorLabel: String, sourceSets: String) =
        buildAnchor(anchor, anchorLabel, sourceSets) {}

    private fun FlowContent.buildAnchor(node: ContentNode) {
        node.anchorLabel?.let { label -> buildAnchor(node.anchor!!, label, node.sourceSetsFilters) }
    }


    override fun FlowContent.buildNavigation(page: PageNode) =
        div(classes = "breadcrumbs") {
            val path = locationProvider.ancestors(page).filterNot { it is RendererSpecificPage }.asReversed()
            if (path.size > 1) {
                buildNavigationElement(path.first(), page)
                path.drop(1).forEach { node ->
                    text("/")
                    buildNavigationElement(node, page)
                }
            }
        }

    private fun FlowContent.buildNavigationElement(node: PageNode, page: PageNode) =
        if (node.isNavigable) buildLink(node, page)
        else text(node.name)

    private fun FlowContent.buildLink(to: PageNode, from: PageNode) =
        locationProvider.resolve(to, from)?.let { path ->
            buildLink(path) {
                text(to.name)
            }
        } ?: span {
            attributes["data-unresolved-link"] = to.name.htmlEscape()
            text(to.name)
        }

    fun FlowContent.buildAnchorCopyButton(pointingTo: String) {
        span(classes = "anchor-wrapper") {
            span(classes = "anchor-icon") {
                attributes["pointing-to"] = pointingTo
            }
            copiedPopup("Link copied to clipboard")
        }
    }

    fun FlowContent.buildLink(
        to: DRI,
        platforms: List<DisplaySourceSet>,
        from: PageNode? = null,
        block: FlowContent.() -> Unit
    ) = locationProvider.resolve(to, platforms.toSet(), from)?.let { buildLink(it, block) }
        ?: run { context.logger.error("Cannot resolve path for `$to` from `$from`"); block() }

    override fun buildError(node: ContentNode) = context.logger.error("Unknown ContentNode type: $node")

    override fun FlowContent.buildLineBreak() = br()

    override fun FlowContent.buildLink(address: String, content: FlowContent.() -> Unit) =
        a(href = address, block = content)

    override fun FlowContent.buildDRILink(
        node: ContentDRILink,
        pageContext: ContentPage,
        sourceSetRestriction: Set<DisplaySourceSet>?
    ) = locationProvider.resolve(node.address, node.sourceSets, pageContext)?.let { address ->
        buildLink(address) {
            buildText(node.children, pageContext, sourceSetRestriction)
        }
    } ?: if (isPartial) {
        templateCommand(ResolveLinkCommand(node.address)) {
            buildText(node.children, pageContext, sourceSetRestriction)
        }
    } else {
        span {
            attributes["data-unresolved-link"] = node.address.toString().htmlEscape()
            buildText(node.children, pageContext, sourceSetRestriction)
        }
    }

    override fun FlowContent.buildCodeBlock(
        code: ContentCodeBlock,
        pageContext: ContentPage
    ) {
        div("sample-container") {
            val codeLang = "lang-" + code.language.ifEmpty { "kotlin" }
            val stylesWithBlock = code.style + TextStyle.Block + codeLang
            pre {
                code(stylesWithBlock.joinToString(" ") { it.toString().toLowerCase() }) {
                    attributes["theme"] = "idea"
                    code.children.forEach { buildContentNode(it, pageContext) }
                }
            }
            /*
            Disable copy button on samples as:
             - it is useless
             - it overflows with playground's run button
             */
            if (!code.style.contains(ContentStyle.RunnableSample)) copyButton()
        }
    }

    override fun FlowContent.buildCodeInline(
        code: ContentCodeInline,
        pageContext: ContentPage
    ) {
        val codeLang = "lang-" + code.language.ifEmpty { "kotlin" }
        val stylesWithBlock = code.style + codeLang
        code(stylesWithBlock.joinToString(" ") { it.toString().toLowerCase() }) {
            code.children.forEach { buildContentNode(it, pageContext) }
        }
    }

    override fun FlowContent.buildText(textNode: ContentText) = buildText(textNode, textNode.style)

    private fun FlowContent.buildText(textNode: ContentText, unappliedStyles: Set<Style>) {
        when {
            textNode.extra[HtmlContent] != null -> {
                consumer.onTagContentUnsafe { raw(textNode.text) }
            }
            unappliedStyles.contains(TextStyle.Indented) -> {
                consumer.onTagContentEntity(Entities.nbsp)
                buildText(textNode, unappliedStyles - TextStyle.Indented)
            }
            unappliedStyles.isNotEmpty() -> {
                val styleToApply = unappliedStyles.first()
                applyStyle(styleToApply) {
                    buildText(textNode, unappliedStyles - styleToApply)
                }
            }
            textNode.hasStyle(ContentStyle.RowTitle) || textNode.hasStyle(TextStyle.Cover) ->
                buildBreakableText(textNode.text)
            else -> text(textNode.text)
        }
    }

    private inline fun FlowContent.applyStyle(styleToApply: Style, crossinline body: FlowContent.() -> Unit) {
        when (styleToApply) {
            TextStyle.Bold -> b { body() }
            TextStyle.Italic -> i { body() }
            TextStyle.Strikethrough -> strike { body() }
            TextStyle.Strong -> strong { body() }
            is TokenStyle -> span("token " + styleToApply.toString().toLowerCase()) { body() }
            else -> body()
        }
    }

    override fun render(root: RootPageNode) {
        shouldRenderSourceSetBubbles = shouldRenderSourceSetBubbles(root)
        super.render(root)
    }

    override fun buildPage(page: ContentPage, content: (FlowContent, ContentPage) -> Unit): String =
        buildHtml(page, page.embeddedResources) {
            div("main-content") {
                id = "content"
                attributes["pageIds"] = "${context.configuration.moduleName}::${page.pageId}"
                content(this, page)
            }
        }

    private val String.isAbsolute: Boolean
        get() = URI(this).isAbsolute

    open fun buildHtml(page: PageNode, resources: List<String>, content: FlowContent.() -> Unit): String {
        val path = locationProvider.resolve(page)
        val pathToRoot = locationProvider.pathToRoot(page)
        return createHTML().prepareForTemplates().html {
            head {
                meta(name = "viewport", content = "width=device-width, initial-scale=1", charset = "UTF-8")
                title(page.name)
                templateCommand(PathToRootSubstitutionCommand("###", default = pathToRoot)) {
                    link(href = "###images/logo-icon.svg", rel = "icon", type = "image/svg")
                }
                templateCommand(PathToRootSubstitutionCommand("###", default = pathToRoot)) {
                    script { unsafe { +"""var pathToRoot = "###";""" } }
                }
                // This script doesn't need to be there but it is nice to have since app in dark mode doesn't 'blink' (class is added before it is rendered)
                script {
                    unsafe {
                        +"""
                    const storage = localStorage.getItem("dokka-dark-mode")
                    const savedDarkMode = storage ? JSON.parse(storage) : false
                    if(savedDarkMode === true){
                        document.getElementsByTagName("html")[0].classList.add("theme-dark")
                    }
                """.trimIndent()
                    }
                }
                resources.forEach {
                    when {
                        it.substringBefore('?').substringAfterLast('.') == "css" ->
                            if (it.isAbsolute) link(
                                rel = LinkRel.stylesheet,
                                href = it
                            )
                            else templateCommand(PathToRootSubstitutionCommand("###", default = pathToRoot)) {
                                link(
                                    rel = LinkRel.stylesheet,
                                    href = "###$it"
                                )
                            }
                        it.substringBefore('?').substringAfterLast('.') == "js" ->
                            if (it.isAbsolute) script(
                                type = ScriptType.textJavaScript,
                                src = it
                            ) {
                                async = true
                            } else templateCommand(PathToRootSubstitutionCommand("###", default = pathToRoot)) {
                                script(
                                    type = ScriptType.textJavaScript,
                                    src = "###$it"
                                ) {
                                    if (it == "scripts/main.js")
                                        defer = true
                                    else
                                        async = true
                                }
                            }
                        it.isImage() -> if (it.isAbsolute) link(href = it)
                        else templateCommand(PathToRootSubstitutionCommand("###", default = pathToRoot)) {
                            link(href = "###$it")
                        }
                        else -> unsafe { +it }
                    }
                }
            }
            body {
                div("navigation-wrapper") {
                    id = "navigation-wrapper"
                    div {
                        id = "leftToggler"
                        span("icon-toggler")
                    }
                    div("library-name") {
                        clickableLogo(page, pathToRoot)
                    }
                    div { templateCommand(ReplaceVersionsCommand(path.orEmpty())) }
                    div("pull-right d-flex") {
                        filterButtons(page)
                        button {
                            id = "theme-toggle-button"
                            span {
                                id = "theme-toggle"
                            }
                        }
                        div {
                            id = "searchBar"
                        }
                    }
                }
                div {
                    id = "container"
                    div {
                        id = "leftColumn"
                        div {
                            id = "sideMenu"
                        }
                    }
                    div {
                        id = "main"
                        content()
                        div(classes = "footer") {
                            span("go-to-top-icon") {
                                a(href = "#content") {
                                    id = "go-to-top-link"
                                }
                            }
                            span {
                                configuration?.footerMessage?.takeIf { it.isNotEmpty() }
                                    ?.let { unsafe { raw(it) } }
                                    ?: text(defaultFooterMessage)
                            }
                            span("pull-right") {
                                span { text("Generated by ") }
                                a(href = "https://github.com/Kotlin/dokka") {
                                    span { text("dokka") }
                                    span(classes = "padded-icon")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This is deliberately left open for plugins that have some other pages above ours and would like to link to them
     * instead of ours when clicking the logo
     */
    open fun FlowContent.clickableLogo(page: PageNode, pathToRoot: String) {
        if (context.configuration.delayTemplateSubstitution && page is ContentPage) {
            templateCommand(PathToRootSubstitutionCommand(pattern = "###", default = pathToRoot)) {
                a {
                    href = "###index.html"
                    templateCommand(
                        ProjectNameSubstitutionCommand(
                            pattern = "@@@",
                            default = context.configuration.moduleName
                        )
                    ) {
                        span {
                            text("@@@")
                        }
                    }
                }
            }
        } else {
            a {
                href = pathToRoot + "index.html"
                text(context.configuration.moduleName)
            }
        }
    }

    private val ContentNode.isAnchorable: Boolean
        get() = anchorLabel != null

    private val ContentNode.anchorLabel: String?
        get() = extra[SymbolAnchorHint]?.anchorName

    private val ContentNode.anchor: String?
        get() = extra[SymbolAnchorHint]?.contentKind?.let { contentKind ->
            (locationProvider as DokkaBaseLocationProvider).anchorForDCI(DCI(dci.dri, contentKind), sourceSets)
        }

    private val isPartial = context.configuration.delayTemplateSubstitution
}

fun List<SimpleAttr>.joinAttr() = joinToString(" ") { it.extraKey + "=" + it.extraValue }

private fun String.stripDiv() = drop(5).dropLast(6) // TODO: Find a way to do it without arbitrary trims

private val PageNode.isNavigable: Boolean
    get() = this !is RendererSpecificPage || strategy != RenderingStrategy.DoNothing

private fun PropertyContainer<ContentNode>.extraHtmlAttributes() = allOfType<SimpleAttr>()

private val ContentNode.sourceSetsFilters: String
    get() = sourceSets.sourceSetIDs.joinToString(" ") { it.toString() }

private val DisplaySourceSet.comparableKey
        get() = sourceSetIDs.merged.let { it.scopeId + it.sourceSetName }
