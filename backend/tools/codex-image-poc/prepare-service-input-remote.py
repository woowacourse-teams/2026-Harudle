import base64,hashlib,io,json,os,re,subprocess,sys,tarfile,tempfile,uuid,zipfile
SOURCE_BASE64="__JAVA_SOURCE_BASE64__"
name="harudle-backend-1"
item=json.loads(subprocess.check_output(["docker","inspect",name],text=True))[0]
settings=dict(value.split("=",1) for value in item["Config"]["Env"] if "=" in value)
pg=os.environ.copy()
pg.update(PGHOST=settings["DB_HOST"],PGPORT=settings.get("DB_PORT","5432"),PGDATABASE=settings.get("DB_NAME","harudle"),PGUSER=settings.get("DB_USERNAME","harudle"),PGPASSWORD=settings["DB_PASSWORD"],PGSSLMODE="require",PGCONNECT_TIMEOUT="8",PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=10000")
query="SELECT json_build_object('promptId',id,'storyboardPrompt',storyboard_prompt_text,'imageStyle',image_style_prompt_text,'referenceKey',image_asset_object_key)::text FROM generation_prompts ORDER BY id DESC LIMIT 1"
snapshot=json.loads(subprocess.check_output(["psql","-X","-q","-A","-t","-v","ON_ERROR_STOP=1","-c",query],env=pg,text=True).strip())
snapshot["diaryText"]="아침에 파란 우산을 들고 출근했다. 비가 내려 우산을 펼쳤는데 강한 바람에 우산이 뒤집혔다. 우산을 다시 펴서 버스 정류장까지 걸어갔고 무사히 버스를 탔다."
archive_bytes=subprocess.check_output(["docker","cp",name+":/app/app.jar","-"])
with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as archive:
    jar_bytes=archive.extractfile(next(m for m in archive.getmembers() if m.isfile())).read()
keys=["DEPLOY_ENV","STORYBOARD_PROVIDER","STORYBOARD_FALLBACK_PROVIDER","STORYBOARD_PRIMARY_TIMEOUT","STORYBOARD_FALLBACK_TIMEOUT","GEMINI_AUTH_MODE","GEMINI_API_KEY","GOOGLE_API_KEY","GOOGLE_APPLICATION_CREDENTIALS","GOOGLE_CLOUD_PROJECT","GOOGLE_CLOUD_LOCATION","GEMINI_STORYBOARD_MODEL","GEMINI_IMAGE_MODEL","GEMINI_STORYBOARD_THINKING_LEVEL","GEMINI_IMAGE_ASPECT_RATIO","GEMINI_MAX_OUTPUT_TOKENS","GEMINI_RETRY_ATTEMPTS","GEMINI_REQUEST_TIMEOUT","AWS_REGION","AWS_DEFAULT_REGION","QWEN_TOKEN_PLAN_API_KEY","QWEN_STORYBOARD_MODEL","QWEN_THINKING_BUDGET","QWEN_MAX_OUTPUT_TOKENS","QWEN_REQUEST_TIMEOUT"]
with tempfile.TemporaryDirectory(prefix="harudle-codex-input-") as directory:
    root=__import__("pathlib").Path(directory)
    root.chmod(0o711)
    reference=root/"reference.bin"
    meta=json.loads(subprocess.check_output(["aws","s3api","get-object","--region",settings.get("AWS_REGION","ap-northeast-2"),"--bucket",settings["S3_BUCKET"],"--key",snapshot.pop("referenceKey"),str(reference)],text=True))
    media=meta.get("ContentType","image/png")
    suffix={"image/png":".png","image/jpeg":".jpg","image/webp":".webp"}.get(media)
    if suffix is None: raise RuntimeError("Unsupported reference media type")
    final_reference=root/("reference"+suffix)
    reference.rename(final_reference)
    snapshot.update(referenceFile=final_reference.name,referenceMediaType=media)
    (root/"input.json").write_text(json.dumps(snapshot,ensure_ascii=False),encoding="utf-8")
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        for member in jar.infolist():
            if not member.filename.startswith(("BOOT-INF/classes/","BOOT-INF/lib/")) or member.is_dir(): continue
            destination=root/member.filename
            if not destination.resolve().is_relative_to(root.resolve()): raise RuntimeError("Invalid archive member")
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(jar.read(member))
    (root/"PrepareServiceInput.java").write_bytes(base64.b64decode(SOURCE_BASE64))
    env_path=root/"runtime.env"
    descriptor=os.open(env_path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,"w") as stream:
        for key in keys:
            if key not in settings: continue
            if "\n" in settings[key] or "\r" in settings[key]: raise RuntimeError("Unsupported multiline runtime value")
            stream.write(key+"="+settings[key]+"\n")
    diagnostic_name="harudle-codex-input-"+uuid.uuid4().hex[:8]
    command=["docker","run","--rm","--name",diagnostic_name,"--memory","512m","--cpus","1","--pull","never","--entrypoint","java","--env-file",str(env_path),"--mount","type=bind,src="+str(root)+",dst=/diagnostic,readonly"]
    if settings.get("GEMINI_AUTH_MODE")=="vertex":
        credential=settings.get("GOOGLE_APPLICATION_CREDENTIALS")
        mount=next(m for m in item.get("Mounts",[]) if m.get("Destination")==credential)
        command+=["--mount","type=bind,src="+mount["Source"]+",dst="+credential+",readonly"]
    command+=[item["Image"],"-Xmx192m","-XX:MaxMetaspaceSize=192m","-Dfile.encoding=UTF-8","-cp","/diagnostic/BOOT-INF/classes:/diagnostic/BOOT-INF/lib/*","/diagnostic/PrepareServiceInput.java"]
    process=None
    try:
        process=subprocess.run(command,capture_output=True,text=True,timeout=150)
        if process.returncode:
            errors=process.stdout+"\n"+process.stderr
            for key in keys:
                if ("KEY" in key or "TOKEN" in key) and settings.get(key): errors=errors.replace(settings[key],"[redacted]")
            sys.stderr.write(errors[-6000:])
            raise RuntimeError("Standalone preparation failed")
        lines=[line[len("SERVICE_INPUT "):] for line in process.stdout.splitlines() if line.startswith("SERVICE_INPUT ")]
        if len(lines)!=1: raise RuntimeError("Expected one preparation result")
        prepared=json.loads(lines[0])
        prepared["deployedJarSha256"]=hashlib.sha256(jar_bytes).hexdigest()
        data=io.BytesIO()
        with zipfile.ZipFile(data,"w",zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("service-input.json",json.dumps(prepared,ensure_ascii=False,indent=2))
            archive.writestr(final_reference.name,final_reference.read_bytes())
        print(base64.b64encode(data.getvalue()).decode(),flush=True)
    finally:
        subprocess.run(["docker","rm","-f",diagnostic_name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
