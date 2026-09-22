package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeakerDirectionTest {
    @Test public void missingDirectionNeverInventsStraightAhead() throws Exception {
        SpeakerDirection direction = new SpeakerDirection();
        assertFalse(direction.observe(new JSONObject("{}"), 1000));
        assertFalse(direction.observe(new JSONObject("{\"doa\":0}"), 1000));
        assertTrue(Double.isNaN(direction.fresh(1000)));
    }
    @Test public void onlyDocumentedNumericDirectionIsFreshForThreeSeconds() throws Exception {
        SpeakerDirection direction = new SpeakerDirection();
        assertFalse(direction.observe(new JSONObject("{\"event_slu_query\":{\"doa\":\"20\"}}"), 1000));
        assertFalse(direction.observe(new JSONObject("{\"event_slu_query\":{\"doa\":181}}"), 1000));
        assertTrue(direction.observe(new JSONObject("{\"event_slu_query\":{\"doa\":-42}}"), 1000));
        assertEquals(-42, direction.fresh(4000), 0);
        assertTrue(Double.isNaN(direction.fresh(4001)));
        assertTrue(Double.isNaN(direction.fresh(999)));
        direction.clear(); assertTrue(Double.isNaN(direction.fresh(1000)));
    }
}
