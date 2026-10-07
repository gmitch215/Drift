package dev.gmitch215.drift.scan.probe

import dev.gmitch215.drift.scan.probe.kotlin.KotlinCatalog

object Catalog {
	val all: List<Probe> = (
		numericProbes + textProbes + collectionProbes + timeProbes + processProbes +
			resourceProbes + KotlinCatalog.all.map { it.probe }
		).sortedBy { it.id }
}
