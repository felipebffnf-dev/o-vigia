# O Vigia v2 (Fabric 1.20.1)

Mod de terror. O Vigia escolhe um jogador como vítima e:
- **copia a skin dele** (com olhos brancos brilhantes por cima);
- **persegue sem parar**, sem precisar te ver, e às vezes **surge atrás de você** quando ninguém está olhando;
- fica **parado enquanto você olha** pra ele, e avança quando você desvia o olhar;
- manda **mensagens falsas no chat** (inclusive com o seu próprio nome, sussurros, "Herobrine joined the game"...);
- toca **sons só pra você**, vindos de trás;
- causa **Escuridão** perto e some ao amanhecer.

## Compilar
JDK 17 instalado, então `./gradlew build` (Windows: `gradlew.bat build`).
O mod fica em `build/libs/o-vigia-1.0.0.jar` (o que NÃO termina em `-sources`).

## Instalar
Pasta `mods` + Fabric Loader + Fabric API 0.92.2+1.20.1.

## Testar
Use o modo **sobrevivência** (em criativo ele não te escolhe como alvo).
`/summon ovigia:watcher` ou o ovo de spawn. Para ver de dia, use `/time set night`.
