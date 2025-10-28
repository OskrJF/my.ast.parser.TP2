package my.second.ast.parser;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cette classe est dédiée à l'analyse avancée d'une application Java pour
 * le calcul du couplage et l'identification de modules, conformément au TP2.
 * Elle utilise Spoon pour parser le code source et construire des métriques structurelles.
 */
public class ModuleAnalyzer {

    // --- CHAMPS PRINCIPAUX ---

    /**
     * Contient la liste de toutes les classes de l'application analysée.
     * Ce champ est initialisé une seule fois dans le constructeur.
     */
    private final List<CtClass<?>> classes;

    /**
     * Matrice d'adjacence représentant le nombre d'appels entre les classes.
     * La structure est une Map où chaque clé est une classe appelante (caller),
     * et la valeur est une autre Map associant une classe appelée (callee)
     * au nombre d'appels de la première vers la seconde.
     * Exemple : callMatrix.get(ClasseA).get(ClasseB) = 5 signifie que A appelle B 5 fois.
     */
    private final Map<CtClass<?>, Map<CtClass<?>, Integer>> callMatrix = new HashMap<>();

    /**
     * Le nombre total d'appels de méthodes *entre classes distinctes* dans toute l'application.
     * Ce dénominateur est essentiel pour calculer la métrique de couplage normalisée.
     */
    private final int totalCalls;


    /**
     * Classe interne statique pour représenter un nœud dans le dendrogramme.
     * Un dendrogramme est l'arbre qui illustre le processus de clustering hiérarchique.
     * Chaque nœud est un "cluster" qui peut contenir une ou plusieurs classes.
     */
    @SuppressWarnings("unused") // mergeCoupling est conservé pour d'éventuelles extensions (ex: visualisation).
    private static class DendrogramNode {
        /** Ensemble des classes contenues dans ce cluster. */
        final Set<CtClass<?>> classes;

        /** Références vers les deux clusters enfants qui ont été fusionnés pour créer celui-ci. Vaut null pour les feuilles de l'arbre. */
        final DendrogramNode child1;
        final DendrogramNode child2;

        /** La valeur de couplage qui a provoqué la fusion de child1 et child2. Utile pour visualiser le dendrogramme. */
        final double mergeCoupling;

        /**
         * Constructeur pour les nœuds "feuilles", c'est-à-dire l'état initial
         * où chaque cluster ne contient qu'une seule classe.
         * @param singleClass La classe unique constituant ce cluster.
         */
        DendrogramNode(CtClass<?> singleClass) {
            this.classes = new HashSet<>(Collections.singletonList(singleClass));
            this.child1 = null; // Pas d'enfants car c'est une feuille
            this.child2 = null;
            this.mergeCoupling = 0.0;
        }

        /**
         * Constructeur pour les nœuds internes, créés par la fusion de deux autres clusters.
         * @param c1 Le premier cluster enfant.
         * @param c2 Le second cluster enfant.
         * @param coupling La valeur de couplage qui a justifié leur fusion.
         */
        DendrogramNode(DendrogramNode c1, DendrogramNode c2, double coupling) {
            this.classes = new HashSet<>(c1.classes); // On regroupe les classes des deux enfants
            this.classes.addAll(c2.classes);
            this.child1 = c1;
            this.child2 = c2;
            this.mergeCoupling = coupling;
        }

        /**
         * Fournit une représentation textuelle du cluster, pratique pour l'affichage.
         * @return Une chaîne de caractères listant les noms des classes du cluster.
         */
        @Override
        public String toString() {
            return classes.stream().map(CtClass::getSimpleName).collect(Collectors.joining(", ", "{", "}"));
        }
    }


    /**
     * Constructeur de l'analyseur. Il lance immédiatement l'analyse statique
     * du projet spécifié par le chemin. C'est une étape coûteuse qui n'est
     * effectuée qu'une seule fois.
     * @param projectPath Le chemin vers le dossier source du projet Java à analyser.
     */
    public ModuleAnalyzer(String projectPath) {
        // 1. Parser le projet avec Spoon pour obtenir le modèle AST complet.
        CtModel model = buildModel(projectPath);
        // 2. Extraire toutes les classes du modèle.
        this.classes = model.getElements(new TypeFilter<>(CtClass.class));
        // 3. Analyser tous les appels de méthodes pour remplir la matrice de couplage et le total.
        this.totalCalls = analyzeCalls();
    }

    /**
     * Configure et exécute Spoon pour parser le projet et construire son modèle AST.
     * @param projectPath Le chemin vers les sources.
     * @return Le modèle AST (`CtModel`) du projet.
     */
    private CtModel buildModel(String projectPath) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(projectPath);
        // setNoClasspath(true) permet d'analyser le code même si les dépendances (jars) ne sont pas fournies.
        // C'est essentiel pour une analyse purement syntaxique.
        launcher.getEnvironment().setNoClasspath(true);
        return launcher.buildModel();
    }

    /**
     * Méthode centrale qui parcourt l'AST pour identifier tous les appels de méthodes
     * et construire la `callMatrix`. Elle est optimisée pour ne parcourir le code qu'une seule fois.
     * @return Le nombre total d'appels inter-classes.
     */
    private int analyzeCalls() {
        int callCount = 0;
        List<CtMethod<?>> methods = new ArrayList<>();
        // Étape 1 : Initialiser la matrice et collecter toutes les méthodes du projet.
        for (CtClass<?> cls : classes) {
            methods.addAll(cls.getMethods());
            callMatrix.put(cls, new HashMap<>()); // Chaque classe a une entrée dans la matrice
        }

        // Étape 2 : Parcourir chaque méthode pour trouver les appels qu'elle effectue.
        for (CtMethod<?> callerMethod : methods) {
            // On vérifie que le type déclarant la méthode est bien une classe (et non une interface, etc.).
            CtType<?> callerType = callerMethod.getDeclaringType();
            if (!(callerType instanceof CtClass)) {
                continue; // On ignore les méthodes non déclarées dans une classe.
            }
            CtClass<?> callerClass = (CtClass<?>) callerType;

            // Récupérer toutes les invocations de méthodes à l'intérieur de la méthode courante.
            List<CtInvocation<?>> invocations = callerMethod.getElements(new TypeFilter<>(CtInvocation.class));
            for (CtInvocation<?> invocation : invocations) {
                // Pour chaque invocation, on cherche la déclaration de la méthode appelée.
                CtExecutable<?> executable = invocation.getExecutable().getDeclaration();
                if (executable instanceof CtMethod) {
                    CtMethod<?> calleeMethod = (CtMethod<?>) executable;

                    // On vérifie également le type de la méthode appelée.
                    CtType<?> calleeType = calleeMethod.getDeclaringType();
                    if (calleeType instanceof CtClass) {
                        CtClass<?> calleeClass = (CtClass<?>) calleeType;
                        // On ne compte que les appels entre classes *différentes*.
                        if (!callerClass.equals(calleeClass)) {
                            // On incrémente le compteur dans la matrice. `merge` simplifie l'opération.
                            callMatrix.get(callerClass).merge(calleeClass, 1, Integer::sum);
                            callCount++; // On incrémente le compteur total.
                        }
                    }
                }
            }
        }
        return callCount;
    }

    /**
     * Calcule la métrique de couplage entre deux classes A et B, selon la formule du TP2.
     * Couplage(A,B) = (appels(A->B) + appels(B->A)) / total_appels_inter_classes
     * @param classA Première classe.
     * @param classB Seconde classe.
     * @return La valeur de couplage normalisée (entre 0 et 1).
     */
    private double calculateCoupling(CtClass<?> classA, CtClass<?> classB) {
        if (totalCalls == 0) return 0;
        // On récupère les nombres d'appels depuis la matrice pré-calculée.
        int callsAToB = callMatrix.getOrDefault(classA, Collections.emptyMap()).getOrDefault(classB, 0);
        int callsBToA = callMatrix.getOrDefault(classB, Collections.emptyMap()).getOrDefault(classA, 0);
        // On applique la formule.
        return (double) (callsAToB + callsBToA) / totalCalls;
    }
    
    /**
     * Calcule le couplage entre deux clusters (groupes de classes).
     * C'est la somme des couplages de toutes les paires de classes possibles entre les deux clusters.
     * @param clusterA Le premier cluster.
     * @param clusterB Le second cluster.
     * @return La valeur de couplage totale entre les deux clusters.
     */
    private double calculateClusterCoupling(DendrogramNode clusterA, DendrogramNode clusterB) {
        double totalCoupling = 0;
        // On somme les couplages pour chaque paire (a, b) où a est dans clusterA et b dans clusterB.
        for (CtClass<?> classA : clusterA.classes) {
            for (CtClass<?> classB : clusterB.classes) {
                totalCoupling += calculateCoupling(classA, classB);
            }
        }
        return totalCoupling;
    }

    /**
     * Exercice 1 : Affiche le graphe de couplage pondéré de l'application.
     * Le graphe est présenté sous forme de liste de paires de classes avec leur valeur de couplage.
     */
    public void printCouplingGraph() {
        System.out.println("\n===== Graphe de Couplage Pondéré (TP2 - Ex1) =====");
        if (totalCalls == 0) {
            System.out.println("Aucun appel inter-classes détecté. Le couplage est nul.");
            return;
        }

        // On parcourt toutes les paires de classes uniques pour éviter les doublons et les calculs inutiles.
        for (int i = 0; i < classes.size(); i++) {
            for (int j = i + 1; j < classes.size(); j++) {
                CtClass<?> classA = classes.get(i);
                CtClass<?> classB = classes.get(j);
                double coupling = calculateCoupling(classA, classB);
                // On n'affiche que les paires avec un couplage non nul.
                if (coupling > 0) {
                    System.out.printf("Couplage(%s, %s) = %.4f\n", classA.getSimpleName(), classB.getSimpleName(), coupling);
                }
            }
        }
        System.out.println("-------------------------------------------------");
    }

    /**
     * Exercice 2 : Identifie les modules de l'application en utilisant un algorithme
     * de clustering hiérarchique ascendant (agglomératif).
     * @param couplingThreshold Le seuil de couplage 'CP' fourni par l'utilisateur.
     */
    public void identifyModules(double couplingThreshold) {
        System.out.printf("\n===== Identification de Modules (TP2 - Ex2) | Seuil CP = %.4f =====\n", couplingThreshold);

        // --- ÉTAPE 1 : Clustering Hiérarchique pour construire le dendrogramme ---
        // Initialisation : chaque classe est un cluster indépendant.
        List<DendrogramNode> clusters = classes.stream().map(DendrogramNode::new).collect(Collectors.toList());

        DendrogramNode root = null;
        // Boucle principale : on fusionne les clusters jusqu'à n'en avoir qu'un seul (la racine).
        while (clusters.size() > 1) {
            DendrogramNode bestC1 = null, bestC2 = null;
            double maxCoupling = -1;

            // Trouver la paire de clusters la plus fortement couplée.
            for (int i = 0; i < clusters.size(); i++) {
                for (int j = i + 1; j < clusters.size(); j++) {
                    double currentCoupling = calculateClusterCoupling(clusters.get(i), clusters.get(j));
                    if (currentCoupling > maxCoupling) {
                        maxCoupling = currentCoupling;
                        bestC1 = clusters.get(i);
                        bestC2 = clusters.get(j);
                    }
                }
            }

            // Fusionner la meilleure paire trouvée.
            if (bestC1 != null) {
                clusters.remove(bestC1);
                clusters.remove(bestC2);
                DendrogramNode newNode = new DendrogramNode(bestC1, bestC2, maxCoupling);
                clusters.add(newNode);
                root = newNode; // Le dernier nœud restant est la racine du dendrogramme.
            } else {
                break; // S'il n'y a plus de paires à fusionner (couplage nul partout), on arrête.
            }
        }

        if (root == null) {
            System.out.println("Pas assez de classes pour former des modules.");
            return;
        }

        // --- ÉTAPE 2 : Identification des modules à partir du dendrogramme ---
        // On parcourt le dendrogramme pour trouver les branches qui respectent le seuil CP.
        List<DendrogramNode> modules = new ArrayList<>();
        findModulesRecursive(root, couplingThreshold, modules);

        System.out.println("Modules identifiés :");
        int moduleCount = 1;
        for (DendrogramNode module : modules) {
             System.out.printf("  - Module %d: %s\n", moduleCount++, module.toString());
        }
        
        // Vérification de la contrainte M/2 (nombre de modules <= nombre de classes / 2).
        int M = classes.size();
        if (modules.size() > M / 2 && M > 1) {
            System.out.printf("Attention: Le nombre de modules (%d) est supérieur à M/2 (%d).\n", modules.size(), M/2);
        }
        System.out.println("-------------------------------------------------");
    }
    
    /**
     * Calcule la cohésion d'un cluster, définie comme la moyenne du couplage
     * de toutes les paires de classes à l'intérieur de ce cluster.
     * C'est cette valeur qui est comparée au seuil CP.
     * @param cluster Le cluster à évaluer.
     * @return La valeur de cohésion (couplage interne moyen).
     */
    private double getAverageInternalCoupling(DendrogramNode cluster) {
        if (cluster.classes.size() < 2) return 0.0; // Un cluster d'une seule classe n'a pas de cohésion.
        
        double totalInternalCoupling = 0;
        List<CtClass<?>> classList = new ArrayList<>(cluster.classes);
        int pairs = 0;

        // On somme le couplage pour chaque paire unique de classes dans le cluster.
        for (int i = 0; i < classList.size(); i++) {
            for (int j = i + 1; j < classList.size(); j++) {
                totalInternalCoupling += calculateCoupling(classList.get(i), classList.get(j));
                pairs++;
            }
        }
        return (pairs == 0) ? 0 : totalInternalCoupling / pairs;
    }

    /**
     * Fonction récursive qui parcourt le dendrogramme pour identifier les modules
     * en fonction du seuil de couplage (CP).
     * @param node Le nœud courant du dendrogramme à analyser.
     * @param threshold Le seuil CP.
     * @param identifiedModules La liste où stocker les modules identifiés.
     */
    private void findModulesRecursive(DendrogramNode node, double threshold, List<DendrogramNode> identifiedModules) {
        if (node == null) return;
        
        // Cas de base : si le nœud est une feuille (une seule classe), il est considéré comme un module.
        if (node.child1 == null || node.child2 == null) {
             identifiedModules.add(node);
             return;
        }

        // On calcule la cohésion interne du module potentiel représenté par le nœud courant.
        double averageCoupling = getAverageInternalCoupling(node);

        // Condition principale de l'exercice : si la cohésion est supérieure au seuil...
        if (averageCoupling > threshold) {
            // ... alors ce nœud est un module valide. On l'ajoute à la liste et on arrête de descendre dans cette branche.
            identifiedModules.add(node);
        } else {
            // ... sinon, le module n'est pas assez cohérent. On continue la recherche récursivement sur ses enfants.
            findModulesRecursive(node.child1, threshold, identifiedModules);
            findModulesRecursive(node.child2, threshold, identifiedModules);
        }
    }
}