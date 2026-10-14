import random
W = """the a to of and in is it you that for on with as this was be at have are not or from but by they we he she
what all were when your can said there use an each which do how their if will up other about out many then them
these so some her would make like him into time has look two more write go see number no way could people my than
first water been call who oil its now find long down day did get come made may part over new sound take only little
work know place year live me back give most very after thing our just name good sentence man think say great where
help through much before line right too mean old any same tell boy follow came want show also around form three small
set put end does another well large must big even such because turn here why ask went men read need land different home
us move try kind hand picture again change off play spell air away animal house point page letter mother answer found
study still learn should america world rock group grow grocery groom growl bot boat bought robot hey hay okay google
siri alexa honey heavy hello hi hate gray great grab crock clock block brock rob bob grog grub cronk""".split()
CONF = ["hey google", "okay google", "hey siri", "alexa", "hey rock", "hey brock", "hey grog", "hey crock", "hey grub",
        "hey groom", "hey great", "a grok", "grok", "hey bob", "hey robot", "hey boat", "grocery store", "hey gray rock",
        "they grow", "hey crow", "hey good", "hi rock", "hey look", "hey frog", "hey broke", "hey grab it", "okay",
        "hey", "hey you", "hey there", "bot", "grok bot", "hey rob", "hey honey"]
POS = {"hey_grok": ["hey grok", "hey grok", "hey, grok", "hey grok.", "hey grok!", "hey grock"],
       "hey_grok_bot": ["hey grok bot", "hey grok bot", "hey, grok bot", "hey grok bot.", "hey grock bot", "hey grok, bot"]}
def sentences(n, seed):
    r = random.Random(seed)
    return [" ".join(r.choice(W) for _ in range(r.randint(3, 12))) for _ in range(n)]
