package my.second.ast.parser;

import java.util.InputMismatchException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Aucun chemin de projet fourni.");
            System.out.println("Usage: java -jar your-jar.jar /path/to/your/java/project/src");
            System.out.println("Sous Eclipse IDE, allez dans : Run → Run Configurations → Arguments → Program Arguments");
            System.out.println("et indiquez le chemin vers vos sources (par ex. /path/to/project/src).");
            return;
        }

        String projectPath = args[0];
        JavaApplicationAnalyzer baseAnalyzer = new JavaApplicationAnalyzer();
        ModuleAnalyzer moduleAnalyzer = new ModuleAnalyzer(projectPath);
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\n================ MENU ================");
            System.out.println("Que voulez-vous faire ?");
            System.out.println("--- Fonctions du TP1 ---");
            System.out.println("1. Afficher l'Arbre AST");
            System.out.println("2. Afficher les Statistiques");
            System.out.println("3. Afficher le Graphe d'Appels simple");
            System.out.println("--- Fonctions du TP2 ---");
            System.out.println("4. Générer le Graphe de Couplage pondéré");
            System.out.println("5. Identifier les Modules (Clustering Hiérarchique)");
            System.out.println("--------------------------------------");
            System.out.println("0. Quitter");
            System.out.print("Entrez votre choix (0-5) : ");

            int choix;
            try {
                choix = scanner.nextInt();
            } catch (InputMismatchException e) {
                System.out.println("Erreur : Entrée invalide. Veuillez entrer un nombre.");
                scanner.next(); // Clear invalid input
                continue;
            }

            switch (choix) {
                case 1:
                    baseAnalyzer.printAST(projectPath);
                    break;
                case 2:
                    baseAnalyzer.printStats(projectPath);
                    break;
                case 3:
                    baseAnalyzer.printCallGraph(projectPath);
                    break;
                case 4:
                    moduleAnalyzer.printCouplingGraph();
                    break;
                case 5:
                    System.out.print("Entrez le seuil de couplage 'CP' (ex: 0.1) : ");
                    try {
                        double threshold = scanner.nextDouble();
                        moduleAnalyzer.identifyModules(threshold);
                    } catch (InputMismatchException e) {
                        System.out.println("Erreur : Seuil invalide. Veuillez entrer une valeur numérique.");
                        scanner.next(); // Clear invalid input
                    }
                    break;
                case 0:
                    System.out.println("Programme terminé.");
                    scanner.close();
                    return;
                default:
                    System.out.println("Choix invalide. Veuillez réessayer.");
            }
        }
    }
}