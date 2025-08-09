# Use Amazon Linux 2 as the base image (as recommended)
FROM amazonlinux:2

# Install build tools, dependencies for FFmpeg, debugging tools, OpenSSL 1.1, and CA certificates
RUN yum update -y && \
    yum groupinstall -y "Development Tools" && \
    yum install -y wget tar gzip bzip2-devel nasm pkg-config cmake3 unzip \
                   fribidi-devel fontconfig-devel freetype-devel \
                   iputils bind-utils mysql \
                   openssl11 openssl11-devel ca-certificates \
                   zlib-devel libffi-devel sqlite-devel readline-devel && \
    ln -sf /usr/bin/cmake3 /usr/bin/cmake && \
    yum clean all

# Add Amazon Corretto repository and install Java 21
RUN rpm --import https://yum.corretto.aws/corretto.key && \
    curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo && \
    yum install -y java-21-amazon-corretto java-21-amazon-corretto-devel

# Create directory for source files
RUN mkdir -p /tmp/ffmpeg_sources

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

# Install Python packages for Whisper and other dependencies with compatible versions
RUN export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:$LD_LIBRARY_PATH && \
    echo "Verifying SSL module before pip install..." && \
    /usr/local/bin/python3.11 -c "import ssl; print('SSL available, version:', ssl.OPENSSL_VERSION)" && \
    echo "GCC version:" && gcc --version && \
    /usr/local/bin/pip3.11 install --upgrade pip setuptools wheel && \
    /usr/local/bin/pip3.11 install "numpy==1.24.4" "scipy==1.10.1" && \
    /usr/local/bin/pip3.11 install "torch==2.0.1" "torchaudio==2.0.2" --index-url https://download.pytorch.org/whl/cpu && \
    /usr/local/bin/pip3.11 install "transformers==4.30.2" && \
    /usr/local/bin/pip3.11 install openai-whisper && \
    /usr/local/bin/pip3.11 install ffmpeg-python python-dotenv requests

# Update library cache and set LD_LIBRARY_PATH
RUN echo "/usr/local/lib" > /etc/ld.so.conf.d/local-libs.conf && \
    echo "/usr/lib64/openssl11" >> /etc/ld.so.conf.d/local-libs.conf && \
    ldconfig

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

# Quick verification of key components
RUN echo "Verifying installations..." && \
    /usr/local/bin/ffmpeg -version | head -1 && \
    /usr/local/bin/python3.11 --version && \
    /usr/local/bin/python3.11 -c "import ssl; print('SSL module available')" && \
    echo "Setup verification complete."

# Set working directory
WORKDIR /app

# Create the expected directory structure for the Python script
RUN mkdir -p /temp/scripts

# Copy the Python script to the expected location
COPY scripts/whisper_subtitle.py /temp/scripts/whisper_subtitle.py

# Also copy to /app/scripts for backup
COPY scripts/whisper_subtitle.py /app/scripts/whisper_subtitle.py

# Make the Python script executable
RUN chmod +x /temp/scripts/whisper_subtitle.py

# Copy the .env file
COPY .env /app/.env

COPY credentials/video-editor-tts-24b472478ab838d2168992684517cacfab4c11da.json /app/credentials/video-editor-tts.json

# Copy the Spring Boot JAR
COPY target/Scenith-0.0.1-SNAPSHOT.jar app.jar

# Expose port 1000 (align with application-prod.properties)
EXPOSE 1000

# Run the application with proper environment setup
ENTRYPOINT ["sh", "-c", "export LD_LIBRARY_PATH=/usr/lib64/openssl11:/usr/local/lib:/usr/lib:/lib && echo 'Starting application...' && echo 'Environment check:' && ls -la /usr/local/bin/ffmpeg && ls -la /usr/local/bin/python3.11 && ls -la /temp/scripts/ && java $JAVA_OPTS -jar app.jar"]