package me.devsaki.hentoid.parsers.images

import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.parsers.getImgSrc
import me.devsaki.hentoid.util.exception.ParseException
import me.devsaki.hentoid.util.formatIntAsStr
import me.devsaki.hentoid.util.network.getOnlineDocument
import org.jsoup.nodes.Element

class TmoParser : BaseImageListParser() {
    companion object {
        fun parseImages(content: Content, thumbs: List<Element>): List<String> {
            val coverParts = content.coverImageUrl.split("/")
            val mediaId = coverParts[coverParts.size - 2]
            // We infer the whole book is stored on the same server
            val serverUrl = "https://imgrojo.tmohentai.com/contents/$mediaId/"
            val result: MutableList<String> = ArrayList()
            var index = 0
            for (e in thumbs) {
                val s = getImgSrc(e)
                if (s.isEmpty()) continue
                result.add(serverUrl + formatIntAsStr(index++, 3) + ".webp")
            }
            return result
        }
    }

    override fun parseImages(content: Content): List<String> {
        // Fetch the book gallery page
        val doc = getOnlineDocument(content.galleryUrl)
            ?: throw ParseException("Document unreachable : " + content.galleryUrl)

        val thumbs = doc.select("img.content-thumbnail-cover").filterNotNull()
        return parseImages(content, thumbs)
    }
}