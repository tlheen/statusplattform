package nav.statusplattform.core.openingHours;

import nav.statusplattform.core.entities.OpeningHoursGroup;
import nav.statusplattform.core.entities.RecordEntity;
import nav.statusplattform.core.enums.ServiceStatus;

import nav.statusplattform.core.repositories.OpeningHoursRepository;
import nav.statusplattform.core.repositories.RecordRepository;
import org.fluentjdbc.DbContext;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;


public class UpTimeCalculator {

    private final RecordRepository recordRepository;
    private final OpeningHoursRepository openingHoursRepository;

    public UpTimeCalculator(DbContext context) {

        this.recordRepository = new RecordRepository(context);
        this.openingHoursRepository = new OpeningHoursRepository(context);
    }


    public UpTimeTotal calculateUpTimeForService(UUID serviceId, TimeSpan timeSpan) throws IllegalStateException {
        LocalDateTime from = timeSpan.from();
        LocalDateTime to = timeSpan.to();
        // Records sorted in chronological order
        List<RecordEntity> records;

        records = recordRepository.getRecordsInTimeSpan(serviceId, from, to);
        if (records.isEmpty()) {
            throw new NullPointerException("Records not found for serviceId: " + serviceId);
        }

        long sumOfActualUptime = 0L; //total actual uptime
        long sumOfExpectedUptime = 0L; //total expected time

        //Obtain the first record
        Optional<RecordEntity> firstRecord = records.stream().findFirst();

        /*Exclude the period of time starting before the requested start date time from the first record as
         this is not to be included as part of the calculation. */
        if (firstRecord.get().getCreated_at().isBefore(from.atZone(ZoneId.systemDefault()))) {
            firstRecord.get().setCreated_at(from.atZone(ZoneId.systemDefault()));
        }

        //Get the Services opening hours start and end times from the data entry start date
        LocalTime ohStart = getOpeningHoursStart(serviceId, from);
        LocalTime ohEnd = getOpeningHoursEnd(serviceId, from);

        //Sum up (A) all the time  service has been UP, and all the time service should have been up
        for (int i = 0; i < records.size() - 1; i++) {
            //Current Record start date Time in localTime
            LocalTime fromCurrentCreatedAtLt = records.get(i).getCreated_at().toLocalTime();
            //Next Record startDate and Time in localTime
            LocalTime toNextCreatedAtLt = records.get(i + 1).getCreated_at().toLocalTime();
            //Current record StartDateTime in zdt
            LocalDateTime fromCurrentCreatedAtLdt = records.get(i).getCreated_at().toLocalDateTime();
            //Next record StartDateTime in zdt
            LocalDateTime toNextCreatedAtLdt = records.get(i + 1).getCreated_at().toLocalDateTime();
            //Retrieve the opening hours Start Time
            LocalDateTime startOfDay = records.get(i).getCreated_at().toLocalDateTime().withHour(ohStart.getHour())
                    .withMinute(ohStart.getMinute());
            //Retrieve the opening hours End Time
            LocalDateTime endOfDay = records.get(i).getCreated_at().toLocalDateTime().withHour(ohEnd.getHour()).withMinute(ohEnd.getMinute());

            //Check if the current record is of uptime; if so, sum it up to actual uptime.
            boolean isValidUptime = records.get(i).getStatus().equals(ServiceStatus.OK);

            //Manage opening hours on the starting day
            if ((localDateTimeDifference(fromCurrentCreatedAtLdt, toNextCreatedAtLdt) > 1)) {
                if (fromCurrentCreatedAtLt.isBefore(ohEnd) && fromCurrentCreatedAtLt.isAfter(ohStart)) {
                    sumOfExpectedUptime += Duration.between(fromCurrentCreatedAtLdt, endOfDay).toMinutes();
                } else if (fromCurrentCreatedAtLt.isBefore(ohStart)) {
                    sumOfExpectedUptime += Duration.between(startOfDay, endOfDay).toMinutes();
                }
            }

            sumOfExpectedUptime += sumFullDays(serviceId, fromCurrentCreatedAtLdt, toNextCreatedAtLdt);

            //partial day on the ending day
            //obtain opening hours for date
            ohStart = getOpeningHoursStart(serviceId, toNextCreatedAtLdt);
            ohEnd = getOpeningHoursEnd(serviceId, toNextCreatedAtLdt);
            if (toNextCreatedAtLt.isBefore(ohEnd) && toNextCreatedAtLt.isAfter(ohStart)) {
                startOfDay = toNextCreatedAtLdt.withHour(ohStart.getHour()).withMinute(ohStart.getMinute());
                sumOfExpectedUptime += Duration.between(startOfDay, toNextCreatedAtLdt).toMinutes();
            } else if (toNextCreatedAtLt.isAfter(ohEnd)) {
                //add the duration of the last date with its respective opening and ending hours
                sumOfExpectedUptime += Duration
                        .between(toNextCreatedAtLdt.withHour(ohStart.getHour()).withMinute(ohStart.getMinute()),
                                toNextCreatedAtLdt.withHour(ohEnd.getHour()).withMinute(ohEnd.getMinute())).toMinutes();
            }

            if (isValidUptime) {
                sumOfActualUptime += sumOfExpectedUptime;
            }
        }

        //Last record
        RecordEntity lastRecord = records.getLast();
        boolean isValidUptime = lastRecord.getStatus().equals(ServiceStatus.OK);

        //get the Services opening hours start and end times from the data entry start date
        ohStart = getOpeningHoursStart(serviceId, lastRecord.getCreated_at().toLocalDateTime());
        ohEnd = getOpeningHoursEnd(serviceId, lastRecord.getCreated_at().toLocalDateTime());

        //Last Record startTime in localTime
        LocalTime lastRecordCreatedAtTime = lastRecord.getCreated_at().toLocalTime();
        //Last record StartDateTime in zdt
        LocalDateTime lastRecordCreatedAtLdt = lastRecord.getCreated_at().toLocalDateTime();
        //Opening hours Start Time in zdt
        LocalDateTime startOfDay = lastRecord.getCreated_at().toLocalDateTime().withHour(ohStart.getHour())
                .withMinute(ohStart.getMinute());
        //Opening hours End Time in zdt
        LocalDateTime endOfDay = lastRecord.getCreated_at().toLocalDateTime().withHour(ohEnd.getHour()).withMinute(ohEnd.getMinute());

        if ((localDateTimeDifference(lastRecord.getCreated_at().toLocalDateTime(), to) > 1)) {
            // Handle partial day on the starting day
            if (lastRecordCreatedAtTime.isBefore(ohEnd) && lastRecordCreatedAtTime.isAfter(ohStart)) {
                sumOfExpectedUptime += Duration.between(lastRecordCreatedAtLdt, endOfDay).toMinutes();
            } else if (lastRecordCreatedAtTime.isBefore(ohStart)) {
                sumOfExpectedUptime += Duration.between(startOfDay, endOfDay).toMinutes();
            }
        }

        sumOfExpectedUptime = sumFullDays(serviceId, lastRecordCreatedAtLdt, to);

        //partial day on the ending day
        //obtain opening hours for date
        ohStart = getOpeningHoursStart(serviceId, to);
        ohEnd = getOpeningHoursEnd(serviceId, to);
        if (to.toLocalTime().isBefore(ohEnd) && to.toLocalTime().isAfter(ohStart)) {
            startOfDay = to.withHour(ohStart.getHour()).withMinute(ohStart.getMinute());
            sumOfExpectedUptime += Duration.between(startOfDay, to).toMinutes();
        } else if (to.toLocalTime().isAfter(ohEnd)) {
            //add the duration of the last date with its respective opening and ending hours
            sumOfExpectedUptime += Duration.between(to.withHour(ohStart.getHour()).withMinute(ohStart.getMinute()), to.withHour(ohEnd.getHour()).withMinute(ohEnd.getMinute())).toMinutes();
        }

        if (isValidUptime) {
            sumOfActualUptime += sumOfExpectedUptime;
        }

        //Actual and Expected Uptime totals
        return new UpTimeTotal(sumOfActualUptime, sumOfExpectedUptime);
    }

    private long sumFullDays(UUID serviceId, LocalDateTime fromCurrentCreatedAtZdt, LocalDateTime toNextCreatedAtLdt) {
        //Sum the duration of time that goes over a period of days
        //Get the opening hours start time and end times
        LocalTime ohStart = getOpeningHoursStart(serviceId, fromCurrentCreatedAtZdt.truncatedTo(ChronoUnit.DAYS).plusDays(1));
        LocalTime ohEnd = getOpeningHoursEnd(serviceId, fromCurrentCreatedAtZdt.truncatedTo(ChronoUnit.DAYS).plusDays(1));
        //Set it on the current date
        fromCurrentCreatedAtZdt = fromCurrentCreatedAtZdt.truncatedTo(ChronoUnit.DAYS).plusDays(1).withHour(ohStart.getHour()).withMinute(ohStart.getMinute());

        long expectedUptimeTotal = 0L;

        // Iterate and calculate the duration of time for full days
        while (fromCurrentCreatedAtZdt.isBefore(toNextCreatedAtLdt.truncatedTo(ChronoUnit.DAYS))) {
            LocalDateTime endOfDay = fromCurrentCreatedAtZdt.withHour(ohEnd.getHour()).withMinute(ohEnd.getMinute());
            expectedUptimeTotal += Duration.between(fromCurrentCreatedAtZdt, endOfDay).toMinutes();

            //get the next day and its corresponding opening hours
            ohStart = getOpeningHoursStart(serviceId, fromCurrentCreatedAtZdt.plusDays(1));
            ohEnd = getOpeningHoursEnd(serviceId, fromCurrentCreatedAtZdt.plusDays(1));
            fromCurrentCreatedAtZdt = fromCurrentCreatedAtZdt.plusDays(1).withHour(ohStart.getHour()).withMinute(ohStart.getMinute());
        }
        return expectedUptimeTotal;
    }

    //get the Services opening hours start and end times from the data entry start date
    private LocalTime getOpeningHoursStart(UUID serviceId, LocalDateTime ldt) {
        return OpeningHoursParser.getOpeningTime(openingHoursToString(serviceId, ldt));
    }

    private LocalTime getOpeningHoursEnd(UUID serviceId, LocalDateTime ldt) {
        return OpeningHoursParser.getClosingTime(openingHoursToString(serviceId, ldt));
    }

    private String openingHoursToString(UUID serviceId, LocalDateTime ldt) {
        Optional<OpeningHoursGroup> group = openingHoursRepository.getOHGroupForService(serviceId);
        OpeningHoursGroup oHGroupEntity = group.orElseThrow();
        return OpeningHoursParser.getOpeninghours(ldt.toLocalDate(), oHGroupEntity);
    }


    static long localDateTimeDifference(LocalDateTime d1, LocalDateTime d2) {
        return ChronoUnit.DAYS.between(d1, d2);
    }
}

//Returns the actual and expected uptimes
final class UpTimeTotal {
    private final long sumOfActualUptime;
    private final long sumOfExpectedUptime;

    public UpTimeTotal(long sumOfActualUptime, long sumOfExpectedUptime) {
        this.sumOfActualUptime = sumOfActualUptime;
        this.sumOfExpectedUptime = sumOfExpectedUptime;
    }

    public long getSumOfActualUptime() {
        return sumOfActualUptime;
    }

    public long getSumOfExpectedUptime() {
        return sumOfExpectedUptime;
    }

}



