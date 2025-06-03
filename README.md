# NekoVideo 🐾🎥

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blueviolet?logo=kotlin) ![Android](https://img.shields.io/badge/Android-9%2B-green?logo=android) ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-1.6-blue?logo=android) ![Media3](https://img.shields.io/badge/Media3-1.4.1-orange?logo=android)

Bem-vindo ao **NekoVideo**, um player de vídeo moderno e intuitivo para Android, desenvolvido com **Kotlin** e **Jetpack Compose**! Organize seus vídeos por pastas, crie playlists dinâmicas e aproveite uma experiência de reprodução fluida com controles de mídia integrados. Perfeito para quem ama vídeos e quer uma solução personalizável! 😺

## ✨ Recursos Principais

- **📂 Gerenciamento por Pastas**: Navegue por seus vídeos organizados em pastas e subpastas, com suporte a formatos como MP4, MKV, AVI e MOV.
- **🔀 Playlists Aleatórias**: Crie playlists instantâneas com vídeos de uma pasta e suas subpastas, embaralhados aleatoriamente com um clique no botão de shuffle.
- **🎮 Controles de Mídia Integrados**: Controle a reprodução via fone de ouvido (play/pause, próximo/anterior) e visualize o player na notificação do sistema ou na área de mídia (como no Xiaomi).
- **📸 Thumbnails Automáticas**: Visualize miniaturas geradas automaticamente para cada vídeo, com duração exibida para facilitar a escolha.
- **📱 Interface Moderna**: Desenvolvida com Jetpack Compose, oferecendo uma UI fluida e responsiva.
- **🔊 Reprodução em Segundo Plano**: Continue assistindo vídeos mesmo ao sair do aplicativo, com suporte a controles via notificação.
- **🗑️ Renomeação de Arquivos**: Selecione e renomeie múltiplos vídeos ou pastas diretamente no app.

## 🚀 Como Começar

### Pré-requisitos
- Android Studio (versão mais recente recomendada)
- Dispositivo ou emulador com Android 9 (API 28) ou superior
- Permissões de armazenamento configuradas no dispositivo

### Instalação
1. **Clone o Repositório**:
   ```bash
   git clone https://github.com/seu-usuario/neko-video.git
   cd neko-video
   ```
2. **Abra no Android Studio**:
   - Importe o projeto no Android Studio.
   - Sincronize as dependências do Gradle.
3. **Configure Permissões**:
   - No `AndroidManifest.xml`, verifique as permissões:
     ```xml
     <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
     <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
     ```
4. **Compile e Execute**:
   - Conecte um dispositivo Android ou use um emulador.
   - Clique em "Run" no Android Studio.

### Configuração Adicional
- **Ícone de Notificação**: Substitua `R.drawable.ic_stat_player` em `MediaPlaybackService.kt` por um ícone personalizado ou use um ícone padrão do Android.
- **Armazenamento**: Certifique-se de que os vídeos estão em pastas acessíveis, como `/Download` ou `/Movies`.

## 🛠️ Tecnologias Utilizadas

- **Kotlin**: Linguagem principal para desenvolvimento.
- **Jetpack Compose**: Para uma UI moderna e declarativa.
- **Media3 (ExoPlayer)**: Reprodução de vídeos com suporte a playlists e controles de mídia.
- **MediaStore**: Acesso a vídeos no armazenamento do dispositivo.
- **Coil**: Carregamento de thumbnails com cache eficiente.
- **Coroutines**: Gerenciamento de operações assíncronas.

## 📸 Capturas de Tela

*(Adicione capturas de tela do app aqui para mostrar a interface!)*  
- Tela de pastas: ![Tela de Pastas](screenshots/folders.png)
- Lista de vídeos: ![Lista de Vídeos](screenshots/video_list.png)
- Player de vídeo: ![Player](screenshots/player.png)

> **Nota**: Crie uma pasta `screenshots` no repositório e adicione imagens reais do app para um visual mais profissional.

### Ideias para Contribuição
- 🖥️ Suporte a rotação de tela baseada na proporção do vídeo.
- 📝 Exibição de metadados dinâmicos na notificação (título do vídeo, miniatura).
- 🔄 Suporte a repetição de playlists.
- 🛠️ Correção de thumbnails durante rolagem rápida.

## 🐞 Reportar Bugs

Encontrou um problema? Abra uma **issue** no GitHub com:
- Descrição do problema
- Passos para reproduzir
- Versão do Android e dispositivo
- Logcat (se aplicável)

## 📜 Licença

Este projeto é licenciado sob a [MIT License](LICENSE). Sinta-se à vontade para usar, modificar e distribuir!

## 🌟 Agradecimentos

- À comunidade **Kotlin** e **Jetpack Compose** por ferramentas incríveis.
- Aos contribuidores que ajudam a construir o NekoVideo! 🐾

---

**NekoVideo** - Assista com estilo, organize com facilidade! 😺🎬
