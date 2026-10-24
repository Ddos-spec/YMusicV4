package io.ymusic.ui

import io.ymusic.remoteconfig.RemoteConfigData

/** The app's links and contact address: the remote config's value when it sends one, else the built-in one. */
internal class AppLinks(config: RemoteConfigData?) {
    val website: String = config?.websiteUrl ?: "https://ymusic.io"
    val telegram: String = config?.telegramUrl ?: "https://t.me/ymusic_io"
    val feedbackEmail: String = config?.feedbackEmail ?: "feedback@ymusic.io"
    val feedbackIssue: String = config?.feedbackIssueUrl ?: "https://github.com/ymusic-io/YMusicV4/issues/new"
    val sourceCode: String = config?.sourceCodeUrl ?: "https://github.com/ymusic-io/YMusicV4"
    val terms: String = config?.termsUrl ?: "https://github.com/ymusic-io/YMusicV4/blob/master/TERMS.md"
    val changelog: String = config?.changelogUrl ?: "https://github.com/ymusic-io/YMusicV4/blob/master/CHANGELOG.md"
}
