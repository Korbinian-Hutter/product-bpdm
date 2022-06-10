package com.catenax.gpdm.component.elastic.impl.service

import com.catenax.gpdm.component.elastic.impl.doc.BusinessPartnerDoc
import com.catenax.gpdm.config.ElasticSearchConfigProperties
import com.catenax.gpdm.entity.BaseEntity
import com.catenax.gpdm.entity.SyncType
import com.catenax.gpdm.service.SyncRecordService
import mu.KotlinLogging
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant
import javax.persistence.EntityManager

/**
 * Provides functionality for managing the Elasticsearch index
 */
@Service
class ElasticSyncService(
    val elasticSyncPageService: ElasticSyncPageService,
    val configProperties: ElasticSearchConfigProperties,
    val entityManager: EntityManager,
    private val syncRecordService: SyncRecordService
) {
    private val logger = KotlinLogging.logger { }

    /**
     * Asynchronous version of [exportPaginated]
     */
    @Async
    fun exportPaginatedAsync(fromTime: Instant, saveState: String?) {
        exportPaginated(fromTime, saveState)
    }

    /**
     * Export new changes of the business partner records to the Elasticsearch index
     *
     * A new change is discovered by comparing the updated timestamp of the business partner record with the time of the last export
     */
    fun exportPaginated(fromTime: Instant, saveState: String?) {
        var page = saveState?.toIntOrNull() ?: 0
        var docsPage: Page<BusinessPartnerDoc>

        logger.info { "Start Elasticsearch export from time '$fromTime' and page '$page'" }

        do {
            try {
                val pageRequest = PageRequest.of(page, configProperties.exportPageSize, Sort.by(BaseEntity::updatedAt.name).ascending())
                docsPage = elasticSyncPageService.exportPartnersToElastic(fromTime, pageRequest)
                page++
                val record = syncRecordService.getOrCreateRecord(SyncType.ELASTIC)
                val newCount = record.count + docsPage.content.size
                syncRecordService.setProgress(record, newCount, newCount.toFloat() / docsPage.totalElements)

                //Clear session after each page import to improve JPA performance
                entityManager.clear()

            } catch (exception: RuntimeException) {
                logger.error(exception) { "Exception encountered on Elasticsearch export" }
                syncRecordService.setSynchronizationError(syncRecordService.getOrCreateRecord(SyncType.ELASTIC), exception.message!!, page.toString())
                throw exception
            }
        } while (docsPage.totalPages > page)

        syncRecordService.setSynchronizationSuccess(syncRecordService.getOrCreateRecord(SyncType.ELASTIC))

        logger.info { "Finished Elasticsearch export successfully" }
    }
}