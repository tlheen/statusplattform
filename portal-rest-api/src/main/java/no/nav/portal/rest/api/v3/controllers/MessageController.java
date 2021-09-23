package no.nav.portal.rest.api.v3.controllers;

import nav.portal.core.repositories.AreaRepository;
import nav.portal.core.repositories.DashboardRepository;
import nav.portal.core.repositories.RecordRepository;
import nav.portal.core.repositories.ServiceRepository;
import org.fluentjdbc.DbContext;

public class MessageController {

    private final AreaRepository areaRepository;
    private final DashboardRepository dashboardRepository;
    private final ServiceRepository serviceRepository;
    private final RecordRepository recordRepository;


    public MessageController(DbContext dbContext) {
        this.areaRepository = new AreaRepository(dbContext);
        this.dashboardRepository = new DashboardRepository(dbContext);
        this.serviceRepository = new ServiceRepository(dbContext);
        this.recordRepository = new RecordRepository(dbContext);
    }
}
