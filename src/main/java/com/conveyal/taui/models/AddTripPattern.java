package com.conveyal.taui.models;

import com.conveyal.r5.analyst.scenario.AddTrips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Add a trip pattern.
 */
public class AddTripPattern extends Modification {
    public static final String type = "add-trip-pattern";
    private static final Logger LOG = LoggerFactory.getLogger(AddTripPattern.class);
    public String getType() {
        return type;
    }

    public List<Segment> segments;

    public boolean bidirectional;

    public List<Timetable> timetables;

    public static class Timetable extends AbstractTimetable {
        /** Dwell time at each stop, seconds */
        public int dwellTime;

        /** Speed, kilometers per hour, for each segment */
        public int[] segmentSpeeds;

        /** Dwell times at specific stops, seconds */
        // using Integer not int because dwell times can be null
        public Integer[] dwellTimes;

        public AddTrips.PatternTimetable toR5 (List<ModificationStop> stops) {
            AddTrips.PatternTimetable pt = this.toBaseR5Timetable();

            // Get hop times
            pt.dwellTimes = ModificationStop.getDwellTimes(stops, this.dwellTimes, dwellTime);
            pt.hopTimes = ModificationStop.getHopTimes(stops);

            return pt;
        }
    }

    public AddTrips toR5 () {
        AddTrips at = new AddTrips();
        at.comment = name;
        LOG.info(name);

        at.bidirectional = bidirectional;
        at.frequencies = new ArrayList<>();

        List<ModificationStop> stops = null;
        for (int i = 0; i < timetables.size(); i++) {
            Timetable tt = timetables.get(i);
            stops = ModificationStop.getStopsFromSegments(segments, tt.segmentSpeeds);
            AddTrips.PatternTimetable pt = tt.toR5(stops);
            at.frequencies.add(pt);
            LOG.info(name);
            LOG.info(Arrays.toString(pt.hopTimes));
            LOG.info(String.valueOf(Arrays.stream(pt.hopTimes).sum()));
        }

        // Values for stop spec are not affected by time table segment speeds
        at.stops = ModificationStop.toStopSpecs(stops);

        return at;
    }
}
