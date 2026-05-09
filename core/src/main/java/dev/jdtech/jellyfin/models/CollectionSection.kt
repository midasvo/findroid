package dev.jdtech.jellyfin.models

data class CollectionSection(val id: Int, val name: UiText, val items: List<FindroidItem>)
