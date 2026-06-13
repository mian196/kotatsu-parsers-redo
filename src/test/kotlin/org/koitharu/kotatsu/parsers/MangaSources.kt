package org.koitharu.kotatsu.parsers

import org.junit.jupiter.params.provider.EnumSource
import org.koitharu.kotatsu.parsers.model.MangaParserSource

// Change 'names' to test specified parsers
@EnumSource(MangaParserSource::class, names = ["KAGANE"], mode = EnumSource.Mode.INCLUDE)
internal annotation class MangaSources
