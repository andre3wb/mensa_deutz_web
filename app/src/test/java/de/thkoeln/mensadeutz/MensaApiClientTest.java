package de.thkoeln.mensadeutz;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MensaApiClientTest {

    @Test
    public void testCleanDishTextRemovesAllergenNumbers() {
        assertEquals("Hähnchen Cordon bleu",
                MensaApiClient.cleanDishText("Hähnchen Cordon bleu (2,8,11,11w,17,18)"));
        assertEquals("Bratensauce",
                MensaApiClient.cleanDishText("Bratensauce (1,21)"));
        assertEquals("Drillinge",
                MensaApiClient.cleanDishText("Drillinge (11)"));
    }

    @Test
    public void testParseGermanPrice() {
        assertEquals(4.20, MensaApiClient.parseGermanPrice("4,20"), 0.001);
        assertEquals(0.85, MensaApiClient.parseGermanPrice("0,85 €"), 0.001);
        assertEquals(1.07, MensaApiClient.parseGermanPrice("1.07"), 0.001);
        assertEquals(0.0, MensaApiClient.parseGermanPrice(""), 0.001);
    }

    @Test
    public void testParseAllergens() {
        String raw = "1=Mit Farbstoff | contains colorants, 18=Enthält Laktose | contains lactose";
        List<String> list = MensaApiClient.parseAllergens(raw);
        assertEquals(2, list.size());
        assertEquals("Mit Farbstoff (1)", list.get(0));
        assertEquals("Enthält Laktose (18)", list.get(1));
    }
}
