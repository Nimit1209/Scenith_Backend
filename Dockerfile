# Use Amazon Linux 2 as the base image (as recommended)
FROM amazonlinux:2

# Install build tools, dependencies for FFmpeg, debugging tools, OpenSSL 1.1, and CA certificates
# Added xz-devel for lzma support in Python
RUN yum update -y && \
    yum groupinstall -y "Development Tools" && \
    yum install -y wget tar gzip bzip2-devel nasm pkg-config cmake3 unzip \
                   fribidi-devel fontconfig-devel freetype-devel \
                   iputils bind-utils mysql \
                   openssl11 openssl11-devel ca-certificates \
                   zlib-devel libffi-devel sqlite-devel readline-devel xz-devel && \
    ln -sf /usr/bin/cmake3 /usr/bin/cmake && \
    yum clean all

# Add Amazon Corretto repository and install Java 21
RUN rpm --import https://yum.corretto.aws/corretto.key && \
    curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo && \
    yum install -y java-21-amazon-corretto java-21-amazon-corretto-devel

# Create directory for source files
RUN mkdir -p /tmp/ffmpeg_sources
# ============================================================================
# IMAGEMAGICK INSTALLATION (NEW SECTION)
# ============================================================================

# Install ImageMagick dependencies (install compatible ghostscript versions together)
RUN yum install -y \
    libjpeg-turbo-devel \
    libpng-devel \
    libtiff-devel \
    giflib-devel \
    libwebp-devel \
    openjpeg2-devel \
    lcms2-devel \
    libxml2-devel \
    librsvg2-devel \
    libtool-ltdl-devel \
    perl

# Install ghostscript with matching versions and exclude i686 to avoid multilib conflicts
RUN yum install -y --setopt=protected_multilib=false \
    ghostscript-9.54.0-9.amzn2.0.13.x86_64 || \
    yum install -y ghostscript.x86_64

# Build and install ImageMagick 7.1.2-2 (Q16-HDRI version to match local)
RUN cd /tmp && \
    wget https://github.com/ImageMagick/ImageMagick/archive/refs/tags/7.1.2-2.tar.gz && \
    tar -xzf 7.1.2-2.tar.gz && \
    cd ImageMagick-7.1.2-2 && \
    ./configure \
        --prefix=/usr/local \
        --with-quantum-depth=16 \
        --enable-hdri \
        --with-modules \
        --with-perl=no \
        --enable-shared \
        --enable-static \
        --with-webp=yes \
        --with-openjp2=yes \
        --with-gslib=yes \
        --with-rsvg=yes && \
    make -j$(nproc) && \
    make install && \
    ldconfig && \
    rm -rf /tmp/7.1.2-2.tar.gz /tmp/ImageMagick-7.1.2-2

# Verify ImageMagick installation
RUN magick -version && \
    echo "ImageMagick installed successfully"

# ============================================================================
# END OF IMAGEMAGICK INSTALLATION
# ============================================================================

# Install LAME (MP3 encoder) from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz && \
    tar -xzf lame-3.100.tar.gz && \
    cd lame-3.100 && \
    ./configure --prefix=/usr/local --enable-shared --enable-nasm && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Install Opus from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/xiph/opus/releases/download/v1.4/opus-1.4.tar.gz && \
    tar -xzf opus-1.4.tar.gz && \
    cd opus-1.4 && \
    ./configure --prefix=/usr/local --enable-shared && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build harfbuzz from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/harfbuzz/harfbuzz/releases/download/8.5.0/harfbuzz-8.5.0.tar.xz && \
    tar -xJf harfbuzz-8.5.0.tar.xz && \
    cd harfbuzz-8.5.0 && \
    ./configure --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build libass from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/libass/libass/releases/download/0.17.3/libass-0.17.3.tar.gz && \
    tar -xzf libass-0.17.3.tar.gz && \
    cd libass-0.17.3 && \
    PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/lib64/pkgconfig:/usr/lib/pkgconfig" ./configure --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build x264 from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://code.videolan.org/videolan/x264/-/archive/master/x264-master.tar.bz2 && \
    tar xjvf x264-master.tar.bz2 && \
    cd x264-master && \
    ./configure --enable-shared --enable-pic --prefix=/usr/local && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build x265 from source
RUN cd /tmp/ffmpeg_sources && \
    wget https://github.com/videolan/x265/archive/master.zip && \
    unzip master.zip && \
    cd x265-master/build/linux && \
    cmake -G "Unix Makefiles" -DCMAKE_INSTALL_PREFIX="/usr/local" -DENABLE_SHARED=ON ../../source && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Install pkg-config development files
RUN yum install -y pkgconfig

# Build FFmpeg with x264, x265, additional codecs, and HTTPS support (using OpenSSL 1.1)
RUN cd /tmp/ffmpeg_sources && \
    wget https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.gz && \
    tar -xzf ffmpeg-7.1.1.tar.gz && \
    cd ffmpeg-7.1.1 && \
    export PKG_CONFIG_PATH="/usr/local/lib/pkgconfig:/usr/lib64/pkgconfig:/usr/lib/pkgconfig" && \
    export CFLAGS="-I/usr/include/openssl11" && \
    export LDFLAGS="-L/usr/lib64/openssl11" && \
    ./configure \
        --prefix=/usr/local \
        --enable-gpl \
        --enable-nonfree \
        --enable-libx264 \
        --enable-libx265 \
        --enable-libass \
        --enable-libfreetype \
        --enable-libmp3lame \
        --enable-libopus \
        --enable-openssl \
        --extra-ldflags="-L/usr/local/lib -L/usr/lib64 -L/usr/lib64/openssl11" \
        --extra-cflags="-I/usr/local/include -I/usr/include -I/usr/include/openssl11" && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Build Python 3.11 with OpenSSL 1.1 support
RUN cd /tmp && \
    wget https://www.python.org/ftp/python/3.11.10/Python-3.11.10.tgz && \
    tar -xzf Python-3.11.10.tgz && \
    cd Python-3.11.10 && \
    export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    export CPPFLAGS="-I/usr/include/openssl11" && \
    export LDFLAGS="-L/usr/lib64/openssl11 -Wl,-rpath,/usr/lib64/openssl11" && \
    export PKG_CONFIG_PATH="/usr/lib64/openssl11/pkgconfig:$PKG_CONFIG_PATH" && \
    ./configure \
        --prefix=/usr/local \
        --enable-optimizations \
        --enable-shared \
        --with-openssl=/usr \
        --with-openssl-rpath=auto \
        --enable-loadable-sqlite-extensions && \
    LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib make -j$(nproc) && \
    LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib make altinstall && \
    ldconfig && \
    ln -sf /usr/local/bin/python3.11 /usr/local/bin/python3 && \
    ln -sf /usr/local/bin/python3.11 /usr/local/bin/python && \
    ln -sf /usr/local/bin/pip3.11 /usr/local/bin/pip3 && \
    ln -sf /usr/local/bin/pip3.11 /usr/local/bin/pip && \
    echo "Testing SSL module..." && \
    LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib /usr/local/bin/python3.11 -c "import ssl; print('SSL module loaded successfully')"

# Solution: Install packages in specific order to resolve dependency conflicts
# Step 1: Install base numerical libraries
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install --upgrade pip setuptools wheel

# Step 2: Install PyTorch ecosystem first (using latest available CPU version)
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "torch==2.6.0+cpu" --index-url https://download.pytorch.org/whl/cpu && \
    /usr/local/bin/pip3.11 install "torchaudio==2.6.0+cpu" --index-url https://download.pytorch.org/whl/cpu

# Step 3: Install numpy with compatible version for ONNX runtime
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "numpy<2.0" "numpy>=1.21.0"

# Step 4: Install ONNX runtime compatible with both whisper and rembg
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "onnxruntime==1.16.3"

# Step 5: Install image processing libraries
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "Pillow==11.3.0" && \
    /usr/local/bin/pip3.11 install "opencv-python-headless" && \
    /usr/local/bin/pip3.11 install "scipy"

# Step 6: Install rembg dependencies manually, then rembg (FIXED)
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "pymatting" "pooch" "tqdm" "requests" && \
    /usr/local/bin/pip3.11 install "jsonschema" "click" "aiohttp" && \
    /usr/local/bin/pip3.11 install "rembg==2.0.67"

# Step 7a: Install tiktoken with a specific version that has prebuilt wheels
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install "tiktoken==0.7.0"

# Step 7b: Install whisper with constraints to prevent numpy upgrade
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    echo "numpy<2.0" > /tmp/constraints.txt && \
    /usr/local/bin/pip3.11 install "openai-whisper==20250625" --constraint /tmp/constraints.txt

# Step 8: Install remaining utility packages
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install ffmpeg-python python-dotenv requests

# Update library cache and set LD_LIBRARY_PATH
RUN echo "/usr/local/lib" > /etc/ld.so.conf.d/local-libs.conf && \
    echo "/usr/lib64/openssl11" >> /etc/ld.so.conf.d/local-libs.conf && \
    ldconfig

# Install yt-dlp for YouTube video downloads
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    /usr/local/bin/pip3.11 install yt-dlp && \
    ln -sf /usr/local/bin/yt-dlp /usr/bin/yt-dlp && \
    echo "yt-dlp installed successfully"

# Verify yt-dlp installation
RUN /usr/local/bin/yt-dlp --version || echo "yt-dlp version check failed"
# Clean up
RUN rm -rf /tmp/ffmpeg_sources /tmp/Python-3.11.10*

# Set environment variables
ENV LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:/usr/lib:/lib
ENV PATH=/usr/local/bin:$PATH
ENV FFMPEG_PATH=/usr/local/bin/ffmpeg
ENV FFPROBE_PATH=/usr/local/bin/ffprobe
ENV PYTHON_PATH=/usr/local/bin/python3.11
ENV JAVA_OPTS="-Xms512m -Xmx1024m"
ENV SPRING_PROFILES_ACTIVE=prod
ENV SSL_CERT_DIR=/etc/pki/tls/certs
ENV SSL_CERT_FILE=/etc/pki/tls/certs/ca-bundle.crt
# Update environment variable for yt-dlp
ENV YT_DLP_PATH=/usr/local/bin/yt-dlp
# Set environment variables for ONNX and image processing
ENV OMP_NUM_THREADS=1
ENV OPENCV_IO_MAX_IMAGE_PIXELS=1073741824
# Set ImageMagick path for the application
ENV IMAGEMAGICK_PATH=/usr/local/bin/magick

# Quick verification of key components including rembg
RUN echo "Verifying installations..." && \
    /usr/local/bin/ffmpeg -version | head -1 && \
    /usr/local/bin/python3.11 --version && \
    /usr/local/bin/python3.11 -c "import ssl; print('SSL module available')" && \
    /usr/local/bin/python3.11 -c "import rembg; print('Rembg module available')" && \
    /usr/local/bin/python3.11 -c "import torch; print('PyTorch version:', torch.__version__)" && \
    /usr/local/bin/python3.11 -c "import PIL; print('Pillow version:', PIL.__version__)" && \
    echo "Setup verification complete."
# Create and set permissions for /temp/videoeditor
RUN mkdir -p /temp/videoeditor/logs /temp/videoeditor/temp && \
    chmod -R 777 /temp
# Set working directory
WORKDIR /app

# Create the expected directory structure for Python scripts
RUN mkdir -p /app/scripts /temp/scripts

# Copy the Python scripts to the expected locations
COPY scripts/remove_background.py /app/scripts/remove_background.py

# Copy the whisper script to the expected locations
COPY scripts/whisper_subtitle.py /temp/scripts/whisper_subtitle.py
COPY scripts/whisper_subtitle.py /app/scripts/whisper_subtitle.py
COPY scripts/compress_media.py /app/scripts/compress_media.py
COPY scripts/convert_media.py /app/scripts/convert_media.py
COPY scripts/whisper_transcribe.py /app/scripts/whisper_transcribe.py
COPY scripts/whisper_transcribe.py /temp/scripts/whisper_transcribe.py

# Make the Python scripts executable
RUN chmod +x /app/scripts/remove_background.py && \
    chmod +x /temp/scripts/whisper_subtitle.py && \
    chmod +x /app/scripts/whisper_subtitle.py && \
    chmod +x /app/scripts/convert_media.py && \
    chmod +x /app/scripts/whisper_transcribe.py && \
    chmod +x /temp/scripts/whisper_transcribe.py && \
    chmod +x /app/scripts/compress_media.py

# Copy the .env file
COPY .env /app/.env

# Copy credentials
COPY credentials/video-editor-tts-24b472478ab838d2168992684517cacfab4c11da.json /app/credentials/video-editor-tts.json
# Copy email templates directory
COPY src/main/resources/email-templates/ /app/resources/email-templates/
# Copy the Spring Boot JAR
COPY target/Scenith-0.0.1-SNAPSHOT.jar app.jar

# Expose port 1000 (align with application-prod.properties)
EXPOSE 1000

# Run the application with proper environment setup
ENTRYPOINT ["sh", "-c", "export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:/usr/lib:/lib && export OMP_NUM_THREADS=1 && echo 'Starting application...' && echo 'Environment check:' && ls -la /usr/local/bin/ffmpeg && ls -la /usr/local/bin/python3.11 && ls -la /usr/local/bin/magick && ls -la /app/scripts/ && java $JAVA_OPTS -jar app.jar"]