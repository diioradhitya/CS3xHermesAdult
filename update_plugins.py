import hashlib, json

with open('/opt/data/work/CS3xHermesAdult/builds/MovieboxProvider.cs3', 'rb') as f:
    data = f.read()
cs3_hash = 'sha256-' + hashlib.sha256(data).hexdigest()
cs3_size = len(data)

with open('/opt/data/work/CS3xHermesAdult/builds/plugins.json') as f:
    plugins = json.load(f)

plugins.append({
    'url': 'https://raw.githubusercontent.com/diioradhitya/CS3xHermesAdult/main/builds/MovieboxProvider.cs3',
    'status': 2,
    'version': 2,
    'name': 'MovieBox',
    'internalName': 'MovieBoxProvider',
    'authors': ['Dio R'],
    'description': 'MovieBox - Movie streaming from movieboxfilm.com',
    'fileSize': cs3_size,
    'repositoryUrl': 'https://github.com/diioradhitya/CS3xHermesAdult',
    'language': 'id',
    'tvTypes': ['Movie', 'TvSeries'],
    'iconUrl': 'https://movieboxfilm.com/favicon.ico',
    'apiVersion': '1',
    'fileHash': cs3_hash
})

with open('/opt/data/work/CS3xHermesAdult/builds/plugins.json', 'w') as f:
    json.dump(plugins, f, indent=2)
print("Plugins JSON updated successfully")
print("File hash:", cs3_hash)
print("File size:", cs3_size)
