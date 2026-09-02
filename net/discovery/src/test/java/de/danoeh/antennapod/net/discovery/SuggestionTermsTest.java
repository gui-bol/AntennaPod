package de.danoeh.antennapod.net.discovery;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Logique pure, testable sur la JVM. Ces cas verrouillent les décisions prises en mesurant
 * l'algorithme sur de vrais flux : sans eux, un mot vide oublié ou un IDF mal borné ne casse
 * rien de visible, il dégrade seulement les suggestions en silence.
 */
public class SuggestionTermsTest {

    private static List<String> terms(String seed, List<String> corpus, int max) {
        List<String> out = new ArrayList<>();
        for (SuggestionTerms.WeightedTerm term : SuggestionTerms.extract(seed, corpus, max)) {
            out.add(term.term);
        }
        return out;
    }

    @Test
    public void tokenizeDropsShortWordsAndStopwords() {
        List<String> tokens = SuggestionTerms.tokenize("Les histoires du petit lapin pour dormir");
        assertTrue(tokens.contains("histoires"));
        assertTrue(tokens.contains("lapin"));
        assertTrue(tokens.contains("dormir"));
        assertFalse("« les » est trop court", tokens.contains("les"));
        assertFalse("« pour » est un mot vide", tokens.contains("pour"));
    }

    @Test
    public void tokenizeKeepsAccents() {
        assertTrue(SuggestionTerms.tokenize("Une émission sur les félins").contains("félins"));
    }

    @Test
    public void tokenizeDropsNumbers() {
        assertFalse(SuggestionTerms.tokenize("Épisode 2024 du podcast").contains("2024"));
    }

    @Test
    public void tokenizeDropsUrls() {
        List<String> tokens = SuggestionTerms.tokenize("Retrouvez-nous sur https://feeds.audiomeans.fr/feed/abc");
        assertFalse("le domaine ne doit pas devenir un terme", tokens.contains("feeds"));
        assertFalse(tokens.contains("audiomeans"));
    }

    @Test
    public void tokenizeDropsHtmlEntities() {
        assertFalse(SuggestionTerms.tokenize("Des histoires&nbsp;pour enfants").contains("nbsp"));
    }

    @Test
    public void tokenizeDropsHostingPlatforms() {
        List<String> tokens = SuggestionTerms.tokenize("Un podcast Audiomeans, aussi sur Spotify et Deezer");
        assertFalse(tokens.contains("audiomeans"));
        assertFalse(tokens.contains("spotify"));
        assertFalse(tokens.contains("deezer"));
    }

    @Test
    public void termCommonToWholeLibraryIsDropped() {
        // « histoires » est dans les trois flux : il ne distingue rien, malgré sa fréquence.
        List<String> corpus = Arrays.asList(
                "histoires histoires lapin dormir",
                "histoires cuisine recettes",
                "histoires cyclisme velo");
        List<String> extracted = terms("histoires histoires lapin dormir", corpus, 5);
        assertFalse("un terme présent partout a un IDF nul", extracted.contains("histoires"));
        assertTrue(extracted.contains("lapin"));
    }

    @Test
    public void rareTermOutranksFrequentOne() {
        List<String> corpus = Arrays.asList(
                "cuisine cuisine cuisine lapin",
                "cuisine recettes",
                "cuisine gastronomie");
        assertEquals("le terme rare passe devant, malgré une fréquence bien moindre",
                "lapin", terms("cuisine cuisine cuisine lapin", corpus, 1).get(0));
    }

    @Test
    public void singleFeedCorpusStillYieldsTerms() {
        // Bibliothèque d'un seul podcast : rien à discriminer, mais il faut quand même des
        // termes, sinon le tout premier utilisateur n'a jamais de suggestions.
        List<String> extracted = terms("lapin dormir histoires", Arrays.asList("lapin dormir histoires"), 5);
        assertFalse(extracted.isEmpty());
    }

    @Test
    public void emptySeedYieldsNothing() {
        assertTrue(terms("", Arrays.asList("lapin"), 5).isEmpty());
    }

    @Test
    public void respectsMaxCount() {
        List<String> corpus = Arrays.asList("alpha beta gamma delta epsilon zeta", "autre chose");
        assertEquals(3, terms("alpha beta gamma delta epsilon zeta", corpus, 3).size());
    }
}
