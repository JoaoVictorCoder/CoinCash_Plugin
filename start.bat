@echo off
title Compilador CoinCash v1.0 - Java 17

echo ============================================
echo Compilador do Plugin CoinCash
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\coincash

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo spigot-api-1.20.1-R0.1-SNAPSHOT.jar esta na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
    set SPIGOT_PATH=spigot-api-1.20.1-R0.1-SNAPSHOT.jar
)

REM Verificar CoinCard.jar (DEPENDÊNCIA OBRIGATÓRIA)
if not exist CoinCard.jar (
    echo ============================================
    echo ERRO: CoinCard.jar nao encontrado!
    echo ============================================
    echo.
    echo O plugin CoinCash REQUER o CoinCard.jar como dependencia!
    echo.
    echo Certifique-se de que o arquivo CoinCard.jar esta na pasta raiz.
    echo.
    pause
    exit /b 1
) else (
    echo [OK] CoinCard.jar encontrado (dependencia obrigatoria)
    set COINCARD_PATH=CoinCard.jar
)

REM Verificar Vault API (opcional para o CoinCard)
if not exist Vault.jar (
    echo [AVISO] Vault.jar nao encontrado na pasta raiz!
    echo O CoinCard requer Vault para funcionar corretamente.
    echo Certifique-se de ter o Vault instalado no servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set VAULT_PATH=
) else (
    echo [OK] Vault API encontrado (opcional)
    set VAULT_PATH=Vault.jar
)

echo.
echo ============================================
echo Compilando CoinCash...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="%SPIGOT_PATH%";"%COINCARD_PATH%"
if defined VAULT_PATH (
    set CLASSPATH=%CLASSPATH%;"%VAULT_PATH%"
)

REM Mostrar classpath para debug
echo Classpath: %CLASSPATH%
echo.

REM Verificar se o arquivo fonte existe
if not exist src\com\foxsrv\coincash\CoinCash.java (
    echo ============================================
    echo ERRO: Arquivo fonte nao encontrado!
    echo ============================================
    echo.
    echo Caminho esperado: src\com\foxsrv\coincash\CoinCash.java
    echo.
    echo Estrutura de diretorios atual:
    echo.
    if exist src (
        echo Conteudo de src:
        dir /s /b src
    ) else (
        echo Pasta src nao encontrada!
    )
    echo.
    echo Criando estrutura de diretorios necessaria...
    mkdir src\com\foxsrv\coincash 2>nul
    echo Por favor, coloque o arquivo CoinCash.java em src\com\foxsrv\coincash\
    pause
    exit /b 1
)

REM Criar arquivo com lista de fontes
dir /s /b src\com\foxsrv\coincash\*.java > sources.txt

REM Compilar com as dependências necessárias
echo Compilando CoinCash.java...
%JAVAC% --release 17 -d out ^
-cp %CLASSPATH% ^
-sourcepath src ^
-encoding UTF-8 ^
@sources.txt

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    echo.
    echo Possiveis causas:
    echo 1 - Erro de sintaxe no codigo
    echo 2 - Versao do Java incorreta
    echo 3 - CoinCard.jar nao encontrado ou incompativel
    echo 4 - Spigot API nao encontrada ou incompativel
    del sources.txt
    pause
    exit /b 1
)

del sources.txt

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Criando plugin.yml manualmente...
echo ============================================
echo.

REM Criar plugin.yml diretamente na pasta out
(
    echo name: CoinCash
    echo version: 1.0.2
    echo main: com.foxsrv.coincash.CoinCash
    echo api-version: 1.20
    echo depend: [CoinCard]
    echo softdepend: [Vault]
    echo author: FoxSRV
    echo description: Cash system for CoinCard plugin - Withdraw and deposit coins as physical notes
    echo.
    echo commands:
    echo   cash:
    echo     description: Main CoinCash command
    echo     usage: /cash
    echo     aliases: [coincash, ccash]
    echo     permission: coincash.use
    echo.
    echo permissions:
    echo   coincash.use:
    echo     description: Allows using /cash command
    echo     default: true
    echo   coincash.admin:
    echo     description: Allows admin commands
    echo     default: op
) > out\plugin.yml

echo [OK] plugin.yml criado

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
echo Criando CoinCash.jar...
%JAR% cf CoinCash.jar com plugin.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\CoinCash.jar
echo.
dir out\CoinCash.jar
echo.
echo ============================================
echo RESUMO DA COMPILACAO:
echo ============================================
echo.
echo - Data/Hora: %date% %time%
echo - Java Version: 17
echo - Spigot API: OK
echo - CoinCard API: OK (OBRIGATORIO)
if defined VAULT_PATH (
    echo - Vault API: OK (para CoinCard)
) else (
    echo - Vault API: NAO ENCONTRADO (necessario para CoinCard)
)
echo - Arquivo fonte: src\com\foxsrv\coincash\CoinCash.java
echo.
echo ============================================
echo ARQUIVOS COMPILADOS:
echo ============================================
echo.
dir /b src\com\foxsrv\coincash\*.java
echo.
echo ============================================
echo REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - Spigot/Paper 1.20+ necessario
echo 2 - Java 17 ou superior
echo 3 - CoinCard.jar instalado no servidor (OBRIGATORIO)
echo 4 - Vault.jar instalado no servidor (OBRIGATORIO para o CoinCard)
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie CoinCard.jar e Vault.jar para a pasta plugins do servidor
echo 2 - Copie out\CoinCash.jar para a pasta plugins do servidor
echo 3 - Reinicie o servidor ou use /reload confirm
echo 4 - Configure o ServerCard no arquivo plugins/CoinCash/config.yml
echo 5 - Os dados serao salvos em plugins/CoinCash/notes.dat
echo.
echo ============================================
echo COMANDOS DO PLUGIN:
echo ============================================
echo.
echo JOGADORES:
echo /cash - Abre o menu principal
echo.
echo ADMIN:
echo /cash reload - Recarrega a configuracao
echo /cash register ^<worth^> [name] - Registra item na mao como nota
echo /cash remove ^<note^> - Remove uma nota registrada
echo /cash list - Lista todas as notas registradas
echo /cash open ^<player^> - Abre o menu para outro jogador
echo.
echo ============================================
echo PERMISSOES:
echo ============================================
echo.
echo coincash.use - Pode usar /cash (default: true)
echo coincash.admin - Pode usar comandos admin (default: op)
echo.
echo ============================================
echo FUNCIONALIDADES:
echo ============================================
echo.
echo - Saque: Converte coins do card em notas fisicas
echo - Deposito: Converte notas fisicas em coins no card
echo - Sistema de fila para evitar conflitos
echo - Cooldown por jogador (configuravel)
echo - Serializacao completa dos itens (preserva NBT)
echo - Suporte a custom model data
echo - Paginacao no menu de saque
echo - Validacao de notas via NBT tags
echo - Integracao total com CoinCard API
echo - BigDecimal com 8 casas decimais para precisao
echo - Processamento assincrono sem lag
echo.
echo ============================================
echo.

pause
