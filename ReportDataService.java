package com.vb.fitnessapp.service;

import com.vb.fitnessapp.domain.ExercisePerformed;
import com.vb.fitnessapp.domain.FoodEaten;
import com.vb.fitnessapp.domain.ReportData;
import com.vb.fitnessapp.domain.User;
import com.vb.fitnessapp.domain.Weight;
import com.vb.fitnessapp.dto.ReportDataDTO;
import com.vb.fitnessapp.dto.converter.ReportDataToReportDataDTO;
import com.vb.fitnessapp.repository.ExercisePerformedRepository;
import com.vb.fitnessapp.repository.FoodEatenRepository;
import com.vb.fitnessapp.repository.ReportDataRepository;
import com.vb.fitnessapp.repository.UserRepository;
import com.vb.fitnessapp.repository.WeightRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.toList;

@Service
public final class ReportDataService {

    private final UserRepository userRepository;
    private final WeightRepository weightRepository;
    private final FoodEatenRepository foodEatenRepository;
    private final ExercisePerformedRepository exercisePerformedRepository;
    private final ReportDataRepository reportDataRepository;
    private final ReportDataToReportDataDTO reportDataDTOConverter;

    /**
     * By default, update tasks should be scheduled for 5 minutes in the future (i.e. 300000 milliseconds).  However,
     * this can be overwritten in the "application.yml" config file... primarily so that unit tests can use
     * a much shorter value.
     */
    @Value("${reportdata.update-delay-in-millis:300000}")
    private long scheduleDelayInMillis;

    /**
     * By default, a background thread should prune outdated entries from the "scheduledUserUpdates" map once every
     * hour.  This can be overwritten in the "application.yml" config file, primarily so that unit tests can
     * use a much shorter value.
     */
    @Value("${reportdata.cleanup-frequency-in-millis:3600000}")
    private long cleanupFrequencyInMillis;

    private final ScheduledThreadPoolExecutor reportDataUpdateThreadPool = new ScheduledThreadPoolExecutor(1);
    private final Map<UUID, ReportDataUpdateEntry> scheduledUserUpdates = new ConcurrentHashMap<>();

    /**
     * The constructor spawns a background thread, which periodically iterates through the "scheduledUserUpdates" map
     * and prunes outdated entries.
     */
    @Autowired
    public ReportDataService(
            final UserRepository userRepository,
            final WeightRepository weightRepository,
            final FoodEatenRepository foodEatenRepository,
            final ExercisePerformedRepository exercisePerformedRepository,
            final ReportDataRepository reportDataRepository,
            final ReportDataToReportDataDTO reportDataDTOConverter
    ) {
        this.userRepository = userRepository;
        this.weightRepository = weightRepository;
        this.foodEatenRepository = foodEatenRepository;
        this.exercisePerformedRepository = exercisePerformedRepository;
        this.reportDataRepository = reportDataRepository;
        this.reportDataDTOConverter = reportDataDTOConverter;

        final Runnable backgroundCleanupThread = () -> {
            while (true) {
                for (Map.Entry<UUID, ReportDataUpdateEntry> entry : scheduledUserUpdates.entrySet()) {
                    final Future future = entry.getValue().getFuture();
                    if (future == null || future.isDone() || future.isCancelled()) {
                        scheduledUserUpdates.remove(entry.getKey());
                    }
                }
                try {
                    Thread.sleep(cleanupFrequencyInMillis);
                } catch (InterruptedException e) {
                    System.out.println("Exception thrown while sleeping in between runs of the ReportData cleanup thread");
                    e.printStackTrace();
                }
            }
        };
        new Thread(backgroundCleanupThread).start();
    }


    public List<ReportDataDTO> findByUser(final UUID userId) {
        final User user = userRepository.findOne(userId);
        final List<ReportData> reportData = reportDataRepository.findByUserOrderByDateAsc(user);
        return reportData.stream()
                .map(reportDataDTOConverter::convert)
                .collect(toList());
    }

    /**
     * Update the ReportData records for a given user, starting on a given date and ending after today's date (in the
     * most common use case, it will be a one-day range consisting of today anyway).
     *
     * I'm not happy about the fact that this method takes as a parameter a User object rather than a UserDTO, as
     * all methods in the service tier should accept and return DTO's rather than actual entities.  However, there is
     * a strange bug in which updates to a user's profile (from the UserService.createUser() and updateUser() methods).
     *
     * The transaction in either of those methods apparently does not commit prior to calling
     * ReportDataService.updateUserFromDate()... and so when this method looks up the user in the database from the DTO,
     * it sees outdated data.  This outdated data is then committed at the end of the ReportData update process,
     * overwriting the original user update.
     *
     * I've tried taking the database save operation in UserService, and breaking it off into its own method annotated
     * with @Transactional.  Although that seems to help when running locally, the problem still persists in production.
     * So simply passing the User object directly (and avoiding the lookup-from-DTO) is an "impure" ploy to fix the
     * problem until I'm able to focus on it again at some point.  At least this method is called only by other classes
     * in the service tier, and not from anywhere in the controller tier that should never touch raw entities.
     */

    public synchronized final Future updateUserFromDate(
            final User user,
            final Date date
    ) {
        // The date is adjusted by the current time in the user's specific time zone.  For example, 2015-03-01 1:00 am
        // on a GMT clock is actually 2015-02-28 8:00 pm if the user is in the "America/New_York" time zone.  So we
        // must ensure that start from 02-28 rather than 03-01.
        //
        // Unfortunately, whenever the user is within that "date-straddling" window of time, this logic will push the
        // date backwards by one day even when dealing with past historic dates rather than the current date.  We
        // might be able to resolve that with even more logic, but I don't think it's that big of a deal for now.
        // Most updates should normally be for the current date rather than a historical revision, so a little extra
        // work in an edge case scenerio may be justified by keeping the logic more simple.
        final Date adjustedDate = adjustDateForTimeZone(date, ZoneId.of(user.getTimeZone()));

        final ReportDataUpdateEntry existingEntry = scheduledUserUpdates.get(user.getId());
        if (existingEntry != null) {
            if (existingEntry.getFuture().isCancelled() || existingEntry.getFuture().isDone()) {
                // There was an update recently scheduled for this user, but it has completed and its entry can be cleaned up.
                scheduledUserUpdates.remove(user.getId());
            } else if (existingEntry.getStartDate().after(adjustedDate)) {
                // There is an update still pending for this user, but its date range is superseded by that of the new
                // update and therefore can be cancelled and cleaned up.
                existingEntry.getFuture().cancel(false);
                scheduledUserUpdates.remove(user.getId());
            } else {
                // There is an update still pending for this user, and it supersedes the new one here.  Do nothing, and
                // let the pending schedule stand.
                return null;
            }
        }

        // Schedule an update for this user, and add an entry in the conflicts list.
        System.out.printf("Scheduling a ReportData update for user [%s] from date [%s] in %d milliseconds%n", user.getEmail(), adjustedDate, scheduleDelayInMillis);
        final ReportDataUpdateTask task = new ReportDataUpdateTask(user, adjustedDate);
        final Future future = reportDataUpdateThreadPool.schedule(task, scheduleDelayInMillis, TimeUnit.MILLISECONDS);
        final ReportDataUpdateEntry newUpdateEntry = new ReportDataUpdateEntry(adjustedDate, future);
        scheduledUserUpdates.put(user.getId(), newUpdateEntry);
        return future;
    }

    public synchronized final boolean isIdle() {
        System.out.printf("%d active threads, %d queued tasks%n", reportDataUpdateThreadPool.getActiveCount(), scheduledUserUpdates.size());
        return reportDataUpdateThreadPool.getActiveCount() == 0 && scheduledUserUpdates.isEmpty();
    }

    /**
     * When the input date is "today", then this method returns the current date in the given time zone (e.g. the input
     * date might be early in the morning of 2015-03-01 in standard GMT, yet still late in the evening of 2015-02-28 in
     * New York).  This method does not modify historic dates earlier than today, because the current time of day today
     * shouldn't cause historic dates to change.
     */

    public final Date adjustDateForTimeZone(final Date date, final ZoneId timeZone) {
        final LocalDateTime localDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
        final LocalDateTime today = LocalDateTime.now();
        Date adjustedDate = (Date) date.clone();
        if (localDate.getDayOfYear() == today.getDayOfYear()) {
            final ZonedDateTime zonedDateTime = ZonedDateTime.now(timeZone);
            adjustedDate = new Date(zonedDateTime.toLocalDate().atStartOfDay(timeZone).toInstant().toEpochMilli());
        }
        return adjustedDate;
    }

    /**
     * A container holding the date range and Future reference for a scheduled user update task.  Used to detect
     * whether or not subsequent tasks supersede those previously scheduled for a user, and to cancel them if so.
     */
    static class ReportDataUpdateEntry {

        private final Date startDate;
        private final Future future;

        public ReportDataUpdateEntry(final Date startDate, final Future future) {
            this.startDate = startDate;
            this.future = future;
        }


        public final Date getStartDate() {
            return startDate;
        }


        public final Future getFuture() {
            return future;
        }
    }

    /**
     * A task that can be scheduled in a background thread, to create or update rows in the ReportData table for a
     * given user, starting from a given date and stopping on the present day.
     *
     * In the future this process could be further optimized, to support:
     *
     * [1] Updating records only between a specific date range (e.g. when the user updates an historical Weight record,
     *     only update ReportData from the date of that record to the date of the next known Weight record).
     * [2] Updating records only on a specific single date (e.g. when the user updates the nutritional information for
     *     a custom food, update that user's ReportData rows only for the dates on which that food had been eaten).
     *
     * However, it's expected that by a wide margin the typical use case will only call this task for today's date.
     * The next most common use case would be where the user has gone some number of days without logging in at all,
     * and so it would be a date range ending on today anyway.  Users making edits to older historical records on
     * arbitrary dates should be an edge case, and probably isn't worth adding further complexity to this design.  A
     * main benefit of this design is that it makes it easier to filter out and discard multiple redundant update
     * requests that come through in a short period of time.  Any re-design would need to account for and provide the
     * same benefit.
     */
    class ReportDataUpdateTask implements Runnable {

        private final User user;
        private final Date startDate;

        public ReportDataUpdateTask(
                final User user,
                final Date startDate
        ) {
            this.user = user;
            this.startDate = startDate;
        }

        @Override
        public void run() {
            final Date today = adjustDateForTimeZone(new Date(new java.util.Date().getTime()), ZoneId.of(user.getTimeZone()));
            LocalDate currentDate = startDate.toLocalDate();

            // Iterate through all dates from the start date through today.
            while (currentDate.toString().compareTo(today.toString()) <= 0) {

                System.out.printf("Creating or updating ReportData record for user [%s] on date [%s]%n", user.getEmail(), currentDate);

                // Get the user's weight on this date, and initialize accumulator variables to hold this date's net calories and net points.
                final Weight mostRecentWeight = weightRepository.findByUserMostRecentOnDate(user, Date.valueOf(currentDate));
                int netCalories = 0;
                double netPoints = 0.0;

                // Iterate over all foods eaten on this date, updating the net calories and net points.
                final List<FoodEaten> foodsEaten = foodEatenRepository.findByUserEqualsAndDateEquals(user, Date.valueOf(currentDate));
                for (final FoodEaten foodEaten : foodsEaten) {
                    netCalories += foodEaten.getCalories();
                    netPoints += foodEaten.getPoints();
                }

                // Iterator over all exercises performed on this date, updating the net calories and net points.
                final List<ExercisePerformed> exercisesPerformed = exercisePerformedRepository.findByUserEqualsAndDateEquals(user, Date.valueOf(currentDate));
                for (final ExercisePerformed exercisePerformed : exercisesPerformed) {
                    netCalories -= ExerciseService.calculateCaloriesBurned(
                            exercisePerformed.getExercise().getMetabolicEquivalent(),
                            exercisePerformed.getMinutes(),
                            mostRecentWeight.getPounds()
                    );
                    netPoints -= ExerciseService.calculatePointsBurned(
                            exercisePerformed.getExercise().getMetabolicEquivalent(),
                            exercisePerformed.getMinutes(),
                            mostRecentWeight.getPounds()
                    );
                }

                // Create a ReportData entry for this date if none already exists, or else updating the existing record for this date.
                final List<ReportData> existingReportDataList = reportDataRepository.findByUserAndDateOrderByDateAsc(user, Date.valueOf(currentDate));
                if (existingReportDataList.isEmpty()) {
                    final ReportData reportData = new ReportData(//NOPMD
                            UUID.randomUUID(),
                            user,
                            Date.valueOf(currentDate),
                            mostRecentWeight.getPounds(),
                            netCalories,
                            netPoints
                    );
                    reportDataRepository.save(reportData);
                } else {
                    final ReportData reportData = existingReportDataList.get(0);
                    reportData.setPounds(mostRecentWeight.getPounds());
                    reportData.setNetCalories(netCalories);
                    reportData.setNetPoints(netPoints);
                    reportDataRepository.save(reportData);
                }

                // Increment the date to the next day.
                currentDate = currentDate.plusDays(1);
            }

            user.setLastUpdatedTime(new Timestamp(System.currentTimeMillis()));
            userRepository.save(user);

            System.out.printf("ReportData update complete for user [%s] from date [%s] to the day prior to [%s]%n", user.getEmail(), startDate, currentDate);
        }
    }

}
