package my.second.ast.parser;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.*;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class JavaApplicationAnalyzer {

    /** Construit le modèle AST à partir du chemin projet */
    private CtModel buildModel(String projectPath) {
        File input = new File(projectPath);
        if (!input.exists() || !input.isDirectory()) {
            throw new IllegalArgumentException("Le chemin fourni n'existe pas ou n'est pas un dossier valide : " + projectPath);
        }

        Launcher launcher = new Launcher(); 
        launcher.addInputResource(projectPath);
        launcher.getEnvironment().setNoClasspath(true);
        return launcher.buildModel();
    }

    /** Affiche l'AST hiérarchique complet */
    public void printAST(String projectPath) {
        CtModel model = buildModel(projectPath);
        List<CtClass<?>> classes = model.getElements(new TypeFilter<>(CtClass.class));

        System.out.println("\n===== AST du projet =====");
        for (CtClass<?> c : classes) {
            String parent = (c.getSuperclass() != null) ? c.getSuperclass().getSimpleName() : "aucun";
            System.out.println("Classe: " + c.getSimpleName() + " (Package: " +
                    (c.getPackage() != null ? c.getPackage().getQualifiedName() : "default") + ", Hérite de: " + parent + ")");

            // Attributs
            if (!c.getFields().isEmpty()) {
                System.out.println("  Attributs:");
                c.getFields().forEach(f -> System.out.println("    - " + f.getType().getSimpleName() + " " + f.getSimpleName()));
            }

            // Méthodes
            if (!c.getMethods().isEmpty()) {
                System.out.println("  Méthodes:");
                c.getMethods().forEach(m -> {
                    int loc = m.getPosition().getEndLine() - m.getPosition().getLine();
                    System.out.println("    - " + m.getSimpleName() + "(" + m.getParameters().size() + " params, " + loc + " lignes)");
                });
            }
        }
    }

    /** Affiche les statistiques complètes */
    public void printStats(String projectPath) {
        CtModel model = buildModel(projectPath);

        List<CtClass<?>> classes = model.getElements(new TypeFilter<>(CtClass.class));
        List<CtMethod<?>> methods = model.getElements(new TypeFilter<>(CtMethod.class));
        Set<CtPackage> packages = new HashSet<>(model.getElements(new TypeFilter<>(CtPackage.class)));

        int totalLOC = classes.stream().mapToInt(c -> c.getPosition().getEndLine() - c.getPosition().getLine()).sum();
        double avgMethodsPerClass = (double) methods.size() / classes.size();
        double avgLOCPerMethod = methods.stream().mapToInt(m -> m.getPosition().getEndLine() - m.getPosition().getLine()).average().orElse(0);
        double avgFieldsPerClass = classes.stream().mapToInt(c -> c.getFields().size()).average().orElse(0);

        int topN = Math.max(1, (int) Math.ceil(classes.size() * 0.1));
        List<CtClass<?>> topMethodClasses = classes.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getMethods().size(), c1.getMethods().size()))
                .limit(topN)
                .collect(Collectors.toList());
        List<CtClass<?>> topFieldClasses = classes.stream()
                .sorted((c1, c2) -> Integer.compare(c2.getFields().size(), c1.getFields().size()))
                .limit(topN)
                .collect(Collectors.toList());
        List<CtClass<?>> intersection = topMethodClasses.stream().filter(topFieldClasses::contains).collect(Collectors.toList());
        int maxParams = methods.stream().mapToInt(m -> m.getParameters().size()).max().orElse(0);

        System.out.println("\n===== Statistiques =====");
        System.out.println("Nombre de classes: " + classes.size());
        System.out.println("Nombre total de lignes de code: " + totalLOC);
        System.out.println("Nombre total de méthodes: " + methods.size());
        System.out.println("Nombre total de packages: " + packages.size());
        System.out.printf("Moyenne méthodes par classe: %.2f\n", avgMethodsPerClass);
        System.out.printf("Moyenne lignes par méthode: %.2f\n", avgLOCPerMethod);
        System.out.printf("Moyenne attributs par classe: %.2f\n", avgFieldsPerClass);

        System.out.println("\n10% classes avec le plus de méthodes:");
        topMethodClasses.forEach(c -> System.out.println("  - " + c.getSimpleName()));

        System.out.println("10% classes avec le plus d'attributs:");
        topFieldClasses.forEach(c -> System.out.println("  - " + c.getSimpleName()));

        System.out.println("Classes dans les deux catégories:");
        intersection.forEach(c -> System.out.println("  - " + c.getSimpleName()));

        System.out.println("Nombre max de paramètres dans toutes les méthodes: " + maxParams);
    }

    /** Affiche le graphe d'appels complet */
    public void printCallGraph(String projectPath) {
        CtModel model = buildModel(projectPath);
        List<CtMethod<?>> methods = model.getElements(new TypeFilter<>(CtMethod.class));

        Map<String, Set<String>> callGraph = new HashMap<>();
        for (CtMethod<?> caller : methods) {
            String callerName = caller.getDeclaringType().getSimpleName() + "." + caller.getSimpleName();
            Set<String> calledMethods = new HashSet<>();

            List<CtInvocation<?>> invocations = caller.getElements(new TypeFilter<>(CtInvocation.class));
            for (CtInvocation<?> inv : invocations) {
                CtExecutable<?> executable = inv.getExecutable().getDeclaration();
                if (executable instanceof CtMethod<?> calleeMethod) {
                    String calleeName = calleeMethod.getDeclaringType().getSimpleName() + "." + calleeMethod.getSimpleName();
                    calledMethods.add(calleeName);
                } else {
                    calledMethods.add(inv.getExecutable().getSimpleName() + " (ext)");
                }
            }

            callGraph.put(callerName, calledMethods);
        }

        System.out.println("\n===== Graphe d'appel =====");
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            System.out.println("Méthode: " + entry.getKey());
            if (entry.getValue().isEmpty()) {
                System.out.println("  -> n'appelle aucune méthode");
            } else {
                entry.getValue().forEach(callee -> System.out.println("  -> " + callee));
            }
        }
    }
}
