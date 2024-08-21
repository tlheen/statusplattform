package nav.statusplattform.core.openingHours;

import nav.statusplattform.core.entities.OpeningHours;

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

        /*
        Returns a map containing the dates in range and its corresponding opening start and end times
         */
        Map<LocalDate, OpeningHours> openingHours = openingHoursRepository.getMapContainingOpeningHoursForTimeSpan(serviceId, timeSpan);


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

        //Get the Services opening hours start and end times from the data entry start


        //Sum up (A) all the time  service has been UP, and all the time service should have been up
        for (int i = 0; i < records.size() - 1; i++) {
            //Get currentRecord record
            RecordEntity currentRecord = records.get(i);
            //Get next record
            RecordEntity nextRecord = records.get(i + 1);

            //Manage opening hours on the starting day
            if ((localDateTimeDifference(currentRecord.dateTime(), nextRecord.dateTime()) > 1)) {
                sumOfExpectedUptime = sumFullDays(openingHours, currentRecord.dateTime().minusDays(1), nextRecord.dateTime());
            }

            sumOfExpectedUptime += sumLastDay(openingHours, nextRecord.dateTime());

            //sum uptime in minutes when service status = ok
            sumOfActualUptime += sumValidUpTime(currentRecord, sumOfExpectedUptime);
        }

        //Last record
        RecordEntity lastRecord = records.getLast();

        //get the Services opening hours start and end times from the data entry start date
        if (localDateTimeDifference(lastRecord.dateTime(), to) > 1) {
            // Handle partial day of the starting day of last record
            sumOfExpectedUptime = sumFullDays(openingHours, lastRecord.dateTime().minusDays(1), to);
        }

        sumOfExpectedUptime += sumLastDay(openingHours, to);

        //sum uptime in minutes when service status = ok
        sumOfActualUptime += sumValidUpTime(lastRecord, sumOfExpectedUptime);


        //Actual and Expected Uptime totals
        return new UpTimeTotal(sumOfActualUptime, sumOfExpectedUptime);
    }

    private long sumFullDays(Map<LocalDate, OpeningHours> openingHours, LocalDateTime currentRecordDateTime, LocalDateTime nextRecordDateTime) {
        //Sum the duration of time that goes over a period of days
        //Get the opening hours start time and end times
        OpeningHours openingHours1 = openingHours.get(currentRecordDateTime.truncatedTo(ChronoUnit.DAYS).plusDays(1).toLocalDate());
        LocalTime ohStartTime = openingHours1.startTime();
        LocalTime ohEndTime = openingHours1.endTime();

        //Set it on the currentRecord date
        currentRecordDateTime = currentRecordDateTime.truncatedTo(ChronoUnit.DAYS).plusDays(1).withHour(ohStartTime.getHour()).withMinute(ohStartTime.getMinute());

        long expectedUptimeTotal = 0L;

        // Iterate and calculate the duration of time for full days
        while (currentRecordDateTime.isBefore(nextRecordDateTime.truncatedTo(ChronoUnit.DAYS))) {
            LocalDateTime endOfDay = currentRecordDateTime.withHour(ohEndTime.getHour()).withMinute(ohEndTime.getMinute());
            expectedUptimeTotal += Duration.between(currentRecordDateTime, endOfDay).toMinutes();

            //get the next day and its corresponding opening hours
            openingHours1 = openingHours.get(currentRecordDateTime.plusDays(1).toLocalDate());
            ohStartTime = openingHours1.startTime();
            ohEndTime = openingHours1.endTime();
            currentRecordDateTime = currentRecordDateTime.plusDays(1).withHour(ohStartTime.getHour()).withMinute(ohStartTime.getMinute());
        }
        return expectedUptimeTotal;
    }

    private long sumLastDay(Map<LocalDate, OpeningHours> openingHours, LocalDateTime endDate) {
        long sumOfExpectedUptime = 0L;

        //partial day on the ending day
        //obtain opening hours for date
        LocalTime ohStartTime = openingHours.get(endDate.toLocalDate()).startTime();
        LocalTime ohEndTime = openingHours.get(endDate.toLocalDate()).endTime();

        if (endDate.toLocalTime().isBefore(ohEndTime) && endDate.toLocalTime().isAfter(ohStartTime)) {
            LocalDateTime startOfDay = endDate.withHour(ohStartTime.getHour()).withMinute(ohStartTime.getMinute());
            sumOfExpectedUptime += Duration.between(startOfDay, endDate).toMinutes();
        } else if (endDate.toLocalTime().isAfter(ohEndTime)) {
            //add the duration of the last date with its respective opening and ending hours
            sumOfExpectedUptime += Duration.between(
                    endDate.withHour(ohStartTime.getHour()).withMinute(ohStartTime.getMinute()),
                    endDate.withHour(ohEndTime.getHour()).withMinute(ohEndTime.getMinute())).toMinutes();
        }

        return sumOfExpectedUptime;

    }

    //sums a record uptime in minutes when service status = ok
    private long sumValidUpTime(RecordEntity record, long sumOfExpectedUptime) {
        long sumValidUpTime = 0L;
        if (record.getStatus().equals(ServiceStatus.OK)) {
            sumValidUpTime += sumOfExpectedUptime;
        }
        return sumValidUpTime;
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



