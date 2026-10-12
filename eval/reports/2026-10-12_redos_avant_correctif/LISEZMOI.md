# RedactorReDoSTest rejoué contre le Redactor AVANT correction (mesure du 2026-10-12)

Procédure :

    git worktree add --detach <tmp> d2c9e89          # Redactor avant le commit 4985539
    cp agent/src/test/java/com/audensiel/legacy/agent/governance/RedactorReDoSTest.java <tmp>/agent/src/test/java/com/audensiel/legacy/agent/governance/
    mvn -f <tmp>/agent/pom.xml test -Dtest=RedactorReDoSTest

Résultat (`resultats.txt`, durées et type d'échec par entrée piégée de 100 000 caractères) :
**7 entrées au-delà d'une seconde** (règles d'affectation et bloc de clé privée) et
**2 StackOverflowError** (domaine d'e-mail et sous-protocoles JDBC : répétition de groupe
récursive), sur 19. Après correction (bornes, commit 4985539), les 19 passent sous la seconde.
