package nav.portal.core.repositories;

import nav.portal.core.entities.AreaEntity;
import nav.portal.core.entities.AreaWithServices;
import nav.portal.core.entities.DashboardEntity;
import nav.portal.core.entities.ServiceEntity;
import nav.portal.core.exceptionHandling.ExceptionUtil;
import org.fluentjdbc.*;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DashboardRepository {


    private final DbContextTable dashboardTable;
    private final DbContextTable dashboardAreaTable;
    private final DbContext dbContext;

    public DashboardRepository(DbContext dbContext) {
        dashboardTable = dbContext.table("dashboard");
        dashboardAreaTable = dbContext.table("dashboard_area");
        this.dbContext = dbContext;
    }

    public UUID save(String name) {
        DatabaseSaveResult<UUID> result = dashboardTable
                .newSaveBuilderWithUUID("id", UUID.randomUUID())
                .setField("name", name)
                .execute();
        return  result.getId();
    }

    public void settAreasOnDashboard(UUID dashboardId, List<UUID> areas) {
        dashboardAreaTable.where("dashboard_id", dashboardId).executeDelete();

        for(int i = 0; i < areas.size(); i++){
            dashboardAreaTable.insert()
                    .setField("dashboard_id", dashboardId)
                    .setField("area_id", areas.get(i))
                    .setField("order_in_dashboard",i)
                    .execute();
        }

    }

    public List<DashboardEntity> getAllDashboardUUIDsAndNames(){
        return dashboardTable.orderedBy("name").stream(DashboardRepository::toDashboard).collect(Collectors.toList());
    }


    public UUID uidFromName(String name) {
        return dashboardTable.where("name", name)
                .singleObject(DashboardRepository::toDashboard)
                .map(dashboard -> dashboard.getId())
                .orElseThrow(() -> new IllegalArgumentException("Not found: Dashboard with name " + name));
    }

    public Map.Entry<DashboardEntity, List<AreaWithServices>> retrieveOne(UUID id) {
        DbContextTableAlias d = dashboardTable.alias("d");
        DbContextTableAlias d2a = dashboardAreaTable.alias("d2a");

        Map<AreaEntity, List<ServiceEntity>> areaEntityListMap = new AreaRepository(dbContext).retrieveAll();
        Map<UUID, AreaEntity> areaById = areaEntityListMap.keySet().stream()
                .collect(Collectors.toMap(AreaEntity::getId, areaEntity -> areaEntity));

        Map<DashboardEntity, List<AreaWithServices>> result = new HashMap<>();

        d.where("id",id)
                .leftJoin(d.column("id"), d2a.column("dashboard_id"))
                .orderBy(d2a.column("order_in_dashboard"))
                .list(row -> {
                    List<AreaWithServices> areasInDashboard = result.computeIfAbsent(toDashboard(row.table(d)), ignored -> new ArrayList<>());
                    Optional.ofNullable(row.getUUID("area_id"))
                            .map(areaById::get)
                            .ifPresent(area -> areasInDashboard.add(new AreaWithServices(area, areaEntityListMap.get(area))));
                    return null;
                });
        return result.entrySet().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not found: Dashboard with UUID " + id));
    }

    public Map.Entry<DashboardEntity, List<AreaWithServices>> retrieveOneFromName(String name) {
        DbContextTableAlias d = dashboardTable.alias("d");
        DbContextTableAlias d2a = dashboardAreaTable.alias("d2a");

        Map<AreaEntity, List<ServiceEntity>> areaEntityListMap = new AreaRepository(dbContext).retrieveAll();
        Map<UUID, AreaEntity> areaById = areaEntityListMap.keySet().stream()
                .collect(Collectors.toMap(AreaEntity::getId, areaEntity -> areaEntity));

        Map<DashboardEntity, List<AreaWithServices>> result = new HashMap<>();

        d.where("name",name)
                .leftJoin(d.column("id"), d2a.column("dashboard_id"))
                .orderBy(d2a.column("order_in_dashboard"))
                .list(row -> {
                    List<AreaWithServices> areasInDashboard = result.computeIfAbsent(toDashboard(row.table(d)), ignored -> new ArrayList<>());
                    Optional.ofNullable(row.getUUID("area_id"))
                            .map(areaById::get)
                            .ifPresent(area -> areasInDashboard.add(new AreaWithServices(area, areaEntityListMap.get(area))));
                    return null;
                });
        return result.entrySet().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not found: Dashboard with name " + name));
    }


    public Map<DashboardEntity, List<AreaWithServices>> retrieveAll() {
        DbContextTableAlias d = dashboardTable.alias("d");
        DbContextTableAlias d2a = dashboardAreaTable.alias("d2a");

        Map<AreaEntity, List<ServiceEntity>> areaEntityListMap = new AreaRepository(dbContext).retrieveAll();
        Map<UUID, AreaEntity> areaById = areaEntityListMap.keySet().stream()
                .collect(Collectors.toMap(AreaEntity::getId, areaEntity -> areaEntity));

        Map<DashboardEntity, List<AreaWithServices>> result = new HashMap<>();

        d.leftJoin(d.column("id"), d2a.column("dashboard_id"))
                .orderBy(d2a.column("order_in_dashboard"))
                .list(row -> {
                    List<AreaWithServices> areasInDashboard = result.computeIfAbsent(toDashboard(row.table(d)), ignored -> new ArrayList<>());
                    Optional.ofNullable(row.getUUID("area_id"))
                            .map(areaById::get)
                            .ifPresent(area -> areasInDashboard.add(new AreaWithServices(area, areaEntityListMap.get(area))));
                    return null;
                });
        return result;
    }






    private static DashboardEntity toDashboard(DatabaseRow row) {
        try {
            return new DashboardEntity()
                    .setId(row.getUUID("id"))
                    .setName(row.getString("name"));
        } catch (SQLException e) {
           throw ExceptionUtil.soften(e);
        }
    }

    public Query query() {
        return new Query(dashboardTable.query());
    }

    public void deleteAreasFromDashboard(UUID id) {
        dashboardAreaTable.where("dashboard_id", id).executeDelete();
    }

    public void deleteDashboard(UUID id) {
        dashboardTable.where("dashboard_id", id).executeDelete();
    }

    public static class Query {

        private final DbContextSelectBuilder query;

        public Query(DbContextSelectBuilder query) {
            this.query = query;
        }

        public Stream<DashboardEntity> stream() {
            return query.stream(DashboardRepository::toDashboard);
        }

        private Query query(DbContextSelectBuilder query) {
            return this;
        }
    }
}
