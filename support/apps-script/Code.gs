/* Cutout feedback. Deploy as a web app: execute as owner, access anyone.
 * Messages go only to the owner's inbox. No images or attachments are accepted.
 * Set the recipient below; never expose SMTP passwords or Google tokens in APK.
 */
var APP_ID = 'dev.kigya.cutout';
var APP_NAME = 'Cutout';
var RECIPIENT = 'kirillborichevskiy@gmail.com';
function validateFeedback(input) {
  if (!input || typeof input !== 'object' || input.appId !== APP_ID) throw new Error('INVALID');
  var email = String(input.email || '').trim();
  var message = String(input.message || '').trim();
  var rating = Number(input.rating);
  var id = String(input.id || '');
  var version = String(input.version || '');
  if (email.length > 254 || !/^[^\s@<>\r\n]+@[^\s@<>\r\n]+\.[^\s@<>\r\n]+$/.test(email) || message.length < 5 || message.length > 4000 || !Number.isInteger(rating) || rating < 0 || rating > 5 || !/^[a-f0-9-]{36}$/.test(id) || !/^\d+\.\d+\.\d+$/.test(version)) throw new Error('INVALID');
  return {email:email,message:message,rating:rating,id:id,version:version};
}
function doPost(event) {
  var lock = LockService.getScriptLock();
  var response;
  try {
    if (!event || !event.postData || event.postData.contents.length > 20000) throw new Error('INVALID');
    var data = validateFeedback(JSON.parse(event.postData.contents));
    if (!lock.tryLock(10000)) throw new Error('BUSY');
    var cache = CacheService.getScriptCache();
    if (cache.get(APP_ID + ':sent:' + data.id)) response = {ok:true,id:data.id};
    else {
      var hash = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, data.email.toLowerCase()).map(function(b){return ('0'+((b+256)%256).toString(16)).slice(-2);}).join('');
      if (cache.get(APP_ID + ':email:' + hash)) throw new Error('RATE_LIMIT');
      var props = PropertiesService.getScriptProperties();
      var today = Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
      var count = props.getProperty('day') === today ? Number(props.getProperty('count') || '0') : 0;
      // Stay below consumer quota; no paid plan or auto-upgrade is used.
      if (count >= 50 || MailApp.getRemainingDailyQuota() < 1) throw new Error('QUOTA');
      props.setProperties({day:today,count:String(count+1)});
      MailApp.sendEmail({to:RECIPIENT,replyTo:data.email,subject:APP_NAME+' '+data.version+' — feedback'+(data.rating ? ' ('+data.rating+'/5)' : ''),body:'App: '+APP_ID+'\nReply email: '+data.email+'\nRating: '+(data.rating || 'not supplied')+'\nVersion: '+data.version+'\n\n'+data.message});
      cache.put(APP_ID+':sent:'+data.id,'1',21600);
      cache.put(APP_ID+':email:'+hash,'1',600);
      response={ok:true,id:data.id};
    }
  } catch(error) {
    var allowed=['INVALID','BUSY','RATE_LIMIT','QUOTA'];
    response={ok:false,code:allowed.indexOf(error.message)>=0 ? error.message : 'SEND_FAILED'};
  } finally { if(lock.hasLock())lock.releaseLock(); }
  return ContentService.createTextOutput(JSON.stringify(response)).setMimeType(ContentService.MimeType.JSON);
}
// Local Node tests execute this same source with controlled Google boundaries.
if(typeof module!=='undefined')module.exports={validateFeedback:validateFeedback,doPost:doPost};
