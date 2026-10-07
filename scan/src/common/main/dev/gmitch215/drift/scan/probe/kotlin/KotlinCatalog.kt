package dev.gmitch215.drift.scan.probe.kotlin

object KotlinCatalog {
	val all: List<KotlinProbe> = (
		floatProbes + integerProbes + mathProbes + charProbes + stringProbes + regexProbes +
			kotlinCollectionProbes + miscProbes
		).sortedBy { it.id }
}
