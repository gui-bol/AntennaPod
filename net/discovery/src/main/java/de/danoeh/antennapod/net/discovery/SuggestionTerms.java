package de.danoeh.antennapod.net.discovery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extrait les termes qui distinguent un podcast du reste de la bibliothèque.
 *
 * Le signal est un TF-IDF dont le corpus est <b>la bibliothèque de l'utilisateur</b> : un terme
 * présent dans beaucoup de ses flux ne distingue rien, même s'il est fréquent dans le flux
 * source. « histoire » est distinctif si un seul podcast en parle, inutile si trois en parlent.
 *
 * Portage du TermExtractor de l'app TV, où l'approche a été mesurée sur de vrais flux.
 */
final class SuggestionTerms {
    private static final int MIN_TERM_LENGTH = 4;

    /** Découpage sur tout ce qui n'est pas une lettre : accents conservés (on interroge du
     *  texte français), nombres écartés (« 2024 » n'apprend rien). */
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}]+");

    /** Sans ça, « feeds.audiomeans.fr » donnerait « feeds » et « audiomeans », qui
     *  ressortiraient comme termes « distinctifs ». */
    private static final Pattern URLS =
            Pattern.compile("(?:https?://|www\\.)\\S+|\\b[\\w-]+\\.(?:com|net|org|fr|ca|io|fm|xml|rss)\\b");

    /** Les descriptions de flux sont du HTML : sans ça « &nbsp; » ressort comme terme
     *  distinctif (observé sur un vrai flux). */
    private static final Pattern HTML_ENTITIES =
            Pattern.compile("&(?:#\\d+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,10});");

    private SuggestionTerms() {
    }

    static final class WeightedTerm {
        final String term;
        final double weight;

        WeightedTerm(String term, double weight) {
            this.term = term;
            this.weight = weight;
        }
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String cleaned = HTML_ENTITIES.matcher(URLS.matcher(text).replaceAll(" ")).replaceAll(" ");
        for (String token : SEPARATORS.split(cleaned.toLowerCase(Locale.ROOT))) {
            if (token.length() >= MIN_TERM_LENGTH && !STOPWORDS.contains(token)
                    && !PLATFORMS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * @param corpusTexts un texte par podcast de la bibliothèque, <b>seed inclus</b> — sinon
     *                    les termes du seed ont tous un df de 1 et l'IDF ne discrimine plus.
     */
    static List<WeightedTerm> extract(String seedText, List<String> corpusTexts, int max) {
        List<String> seedTokens = tokenize(seedText);
        if (seedTokens.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : seedTokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }
        int maxFrequency = 1;
        for (int frequency : termFrequency.values()) {
            maxFrequency = Math.max(maxFrequency, frequency);
        }
        // df = nombre de podcasts de la bibliothèque où le terme apparaît.
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String text : corpusTexts) {
            for (String token : new HashSet<>(tokenize(text))) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        List<WeightedTerm> terms = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            // sqrt sur la fréquence : un terme répété 20 fois n'est pas 20 fois plus
            // significatif qu'un terme vu une fois.
            double tf = Math.sqrt((double) entry.getValue() / maxFrequency);
            double weight = tf * idf(
                    documentFrequency.getOrDefault(entry.getKey(), 0), corpusTexts.size());
            if (weight > 0) {
                terms.add(new WeightedTerm(entry.getKey(), weight));
            }
        }
        terms.sort((a, b) -> a.weight == b.weight
                ? a.term.compareTo(b.term) : Double.compare(b.weight, a.weight));
        return terms.size() > max ? new ArrayList<>(terms.subList(0, max)) : terms;
    }

    /** Un terme présent dans <i>tous</i> les podcasts tombe à 0 et disparaît. Avec un seul
     *  podcast en corpus il n'y a rien à discriminer : poids neutre. */
    private static double idf(int documentFrequency, int corpusSize) {
        if (corpusSize <= 1) {
            return 1.0;
        }
        int df = Math.max(1, Math.min(documentFrequency, corpusSize));
        return Math.log((double) corpusSize / df);
    }

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "alors", "après", "aussi", "autre", "autres", "avaient", "avait", "avant", "avec",
            "avoir", "beaucoup", "bien", "cela", "cent", "cependant", "certain", "certaine",
            "certains", "ces", "cette", "ceux", "chaque", "chez", "comme", "comment", "dans",
            "depuis", "des", "deux", "dire", "donc", "dont", "elle", "elles", "encore", "entre",
            "est", "etaient", "etait", "etre", "eux", "fait", "faire", "faut", "fois", "font",
            "grand", "grande", "hors", "ici", "être", "était", "étaient", "jamais", "jour",
            "leur", "leurs", "lorsque", "lui", "mais", "malgré", "meme", "même", "mes", "moins",
            "mon", "notre", "nous", "ont", "par", "parce", "pas", "pendant", "peut", "peuvent",
            "plus", "plusieurs", "pour", "pourquoi", "pouvoir", "premier", "première", "puis",
            "quand", "que", "quel", "quelle", "quelques", "qui", "quoi", "sans", "ses", "seul",
            "seulement", "sous", "sur", "tandis", "tous", "tout", "toute", "toutes", "trop",
            "très", "une", "vers", "voici", "voilà", "vont", "votre", "vous", "aujourd", "hui",
            "about", "after", "again", "against", "all", "also", "and", "any", "are", "because",
            "been", "before", "being", "below", "between", "both", "but", "can", "did", "does",
            "doing", "down", "during", "each", "few", "for", "from", "further", "had", "has",
            "have", "having", "her", "here", "hers", "him", "his", "how", "into", "its", "itself",
            "just", "more", "most", "not", "now", "off", "once", "only", "other", "our", "ours",
            "out", "over", "own", "same", "she", "should", "some", "such", "than", "that", "the",
            "their", "them", "then", "there", "these", "they", "this", "those", "through", "too",
            "under", "until", "very", "was", "were", "what", "when", "where", "which", "while",
            "who", "whom", "why", "will", "with", "you", "your", "yours",
            "audio", "balado", "baladodiffusion", "chronique", "emission", "épisode", "episode",
            "episodes", "épisodes", "entrevue", "entrevues", "extrait", "https", "http",
            "interview", "interviews", "podcast", "podcasts", "saison", "series", "série",
            "serie", "show", "www"));

    /** Hébergeurs et plateformes : présents dans presque toutes les descriptions (« un podcast
     *  Audiomeans »), l'IDF ne les élimine pas si un seul flux les mentionne. C'est de la
     *  plomberie, jamais un sujet — chercher « audiomeans » ne remonte que le même hébergeur. */
    private static final Set<String> PLATFORMS = new HashSet<>(Arrays.asList(
            "acast", "amazon", "anchor", "apple", "art19", "audible", "audiomeans", "ausha",
            "buzzsprout", "captivate", "castbox", "deezer", "iheart", "iheartradio", "libsyn",
            "megaphone", "omnystudio", "patreon", "podbean", "podcloud", "podtrac", "redcircle",
            "simplecast", "soundcloud", "spotify", "spreaker", "stitcher", "transistor", "youtube"));
}
