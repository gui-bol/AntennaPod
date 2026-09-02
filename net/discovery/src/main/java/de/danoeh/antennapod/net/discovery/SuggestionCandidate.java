package de.danoeh.antennapod.net.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Candidat de suggestion, avec les champs dont {@link PodcastSearchResult} ne dispose pas —
 * description, langue et catégories — nécessaires au filtrage et au classement.
 *
 * Portage du SimilarityRanker de l'app TV : les méthodes de classement vivent ici pour rester
 * au plus près des données qu'elles notent.
 */
final class SuggestionCandidate {
    /** Un terme trouvé dans le titre compte double : signal bien plus fort qu'une occurrence
     *  perdue dans une description de 2000 caractères. */
    private static final double TITLE_BOOST = 2.0;

    final String title;
    final String imageUrl;
    final String feedUrl;
    final String author;
    final String description;
    final String language;
    final Set<String> categories;

    SuggestionCandidate(String title, String imageUrl, String feedUrl, String author,
                        String description, String language, Set<String> categories) {
        this.title = title;
        this.imageUrl = imageUrl;
        this.feedUrl = feedUrl;
        this.author = author;
        this.description = description;
        this.language = language;
        this.categories = categories;
    }

    PodcastSearchResult toResult() {
        return new PodcastSearchResult(title, imageUrl, feedUrl, author);
    }

    /** Score pondéré et nombre de termes recoupés, en un seul parcours. */
    private double[] match(List<SuggestionTerms.WeightedTerm> terms) {
        if (terms.isEmpty()) {
            return new double[] {0, 0};
        }
        Set<String> titleTokens = new HashSet<>(SuggestionTerms.tokenize(title + " " + author));
        Set<String> bodyTokens = new HashSet<>(SuggestionTerms.tokenize(description));
        double total = 0;
        int matched = 0;
        for (SuggestionTerms.WeightedTerm term : terms) {
            if (titleTokens.contains(term.term)) {
                total += term.weight * TITLE_BOOST;
                matched++;
            } else if (bodyTokens.contains(term.term)) {
                total += term.weight;
                matched++;
            }
        }
        return new double[] {total, matched};
    }

    /**
     * Garde les candidats partageant une catégorie avec le podcast source, puis trie par
     * recouvrement lexical décroissant.
     *
     * <p>Le filtre par catégorie est décisif, mesuré sur de vrais flux : la similarité lexicale
     * seule ramène des podcasts qui partagent un <i>mot</i> et non un <i>sujet</i> — chercher
     * « lapin » pour un podcast d'histoires pour enfants remontait « Rabbi Daniel Lapin »
     * (Religion) et « RTL soir » (Actualité), soit environ 1 pertinent sur 12 ; avec le filtre,
     * 10 sur 11. La catégorie est un mauvais signal pour <i>trouver</i> des candidats — c'est
     * l'erreur que corrige ce portage — mais un excellent garde-fou pour en <i>écarter</i>.
     *
     * <p>[minMatchedTerms] est l'autre filtre de précision : les requêtes portant sur des termes
     * isolés, l'ensemble de candidats est large et surtout composé de podcasts ne partageant
     * qu'un mot. L'appelant redescend à 1 si la liste devient trop courte.
     */
    static List<SuggestionCandidate> rank(List<SuggestionCandidate> candidates,
                                          List<SuggestionTerms.WeightedTerm> terms,
                                          Set<String> seedCategories,
                                          int minMatchedTerms) {
        List<double[]> scores = new ArrayList<>();
        List<SuggestionCandidate> kept = new ArrayList<>();
        for (SuggestionCandidate candidate : candidates) {
            // Catégories du seed inconnues : on ne filtre pas plutôt que de tout perdre.
            if (!seedCategories.isEmpty() && !candidate.categories.isEmpty()) {
                boolean shares = false;
                for (String category : candidate.categories) {
                    if (seedCategories.contains(category)) {
                        shares = true;
                        break;
                    }
                }
                if (!shares) {
                    continue;
                }
            }
            double[] score = candidate.match(terms);
            if (score[1] >= minMatchedTerms && score[0] > 0) {
                kept.add(candidate);
                scores.add(score);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> Double.compare(scores.get(b)[0], scores.get(a)[0]));
        List<SuggestionCandidate> sorted = new ArrayList<>();
        for (int index : order) {
            sorted.add(kept.get(index));
        }
        return sorted;
    }

    /**
     * Remonte la langue de l'appareil. Le reste n'entre qu'en complément et ne dépasse jamais
     * la moitié de la liste — sauf s'il n'y a aucun résultat dans la langue voulue, auquel cas
     * le podcast source est probablement lui-même étranger et tout filtrer donnerait le vide.
     */
    static List<SuggestionCandidate> preferLanguage(List<SuggestionCandidate> results,
                                                    String prefix, int minPreferred) {
        List<SuggestionCandidate> preferred = new ArrayList<>();
        List<SuggestionCandidate> others = new ArrayList<>();
        for (SuggestionCandidate candidate : results) {
            if (candidate.language.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                preferred.add(candidate);
            } else {
                others.add(candidate);
            }
        }
        if (preferred.size() >= minPreferred) {
            return preferred;
        }
        int allowed = preferred.isEmpty() ? others.size() : preferred.size();
        preferred.addAll(others.subList(0, Math.min(allowed, others.size())));
        return preferred;
    }
}
