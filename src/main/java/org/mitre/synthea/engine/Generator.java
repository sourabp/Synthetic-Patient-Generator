package org.mitre.synthea.engine;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.mitre.synthea.datastore.DataStore;
import org.mitre.synthea.export.Exporter;
import org.mitre.synthea.helpers.LocalConfig;
import org.mitre.synthea.modules.DeathModule;
import org.mitre.synthea.modules.EncounterModule;
import org.mitre.synthea.modules.LifecycleModule;
import org.mitre.synthea.world.agents.CommunityHealthWorker;
import org.mitre.synthea.world.agents.Hospital;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.Costs;
import org.mitre.synthea.world.concepts.VitalSign;
import org.mitre.synthea.world.geography.Demographics;
import org.mitre.synthea.world.geography.Location;

/**
 * Generator creates a population by running the generic modules each timestep per Person.
 */
public class Generator {

	public final long ONE_HUNDRED_YEARS = 100l * TimeUnit.DAYS.toMillis(365);
	public DataStore database;
	public List<CommunityHealthWorker> chws;
	public long numberOfPeople;
	public final int MAX_TRIES = 10;
	public long seed;
	private Random random;
	public long timestep;
	public long stop;
	public Map<String,AtomicInteger> stats;
	public Map<String,Demographics> demographics=new HashMap<String, Demographics>();
	private String logLevel;
	
	public Generator() throws IOException
	{
		int population = Integer.parseInt( LocalConfig.get("generate.default_population", "1") );
		init(population, System.currentTimeMillis());
	}
	public Generator(int population, String demographicConfigString) throws IOException{
		this.demographics = Demographics.loadByContent(demographicConfigString);
		init(population,System.currentTimeMillis());
	}
	public Generator(int population) throws IOException
	{
		init(population, System.currentTimeMillis());
	}
	public Generator(int population, long seed) throws IOException
	{
		init(population, seed);
	}
	private void init(int population, long seed) throws IOException
	{
		String dbType = LocalConfig.get("generate.database_type");
		
		switch(dbType)
		{
		case "in-memory":
			this.database = new DataStore(false);
			break;
		case "file":
			this.database = new DataStore(true);
			break;
		case "none":
			this.database = null;
			break;
		default:
			throw new IllegalArgumentException("Unexpected value for config setting generate.database_type: '" + dbType + "' . Valid values are file, in-memory, or none.");
		}
		
		this.numberOfPeople = population;
		this.chws = Collections.synchronizedList(new ArrayList<CommunityHealthWorker>());
		this.seed = seed;
		this.random = new Random(seed);
		this.timestep = Long.parseLong( LocalConfig.get("generate.timestep") );
		this.stop = System.currentTimeMillis();
		if(this.demographics.isEmpty()){
			this.demographics = Demographics.loadByName( LocalConfig.get("generate.demographics.default_file"));
		}

		this.logLevel = LocalConfig.get("generate.log_patients.detail", "simple");
		
		this.stats = Collections.synchronizedMap(new HashMap<String,AtomicInteger>());
		stats.put("alive", new AtomicInteger(0));
		stats.put("dead", new AtomicInteger(0));
		
		// initialize hospitals
		Hospital.loadHospitals();
		Module.getModules(); // ensure modules load early
		CommunityHealthWorker.workers.size(); // ensure CHWs are set early
		Costs.loadCostData();
	}
	public String run()
	{		
		ExecutorService threadPool = Executors.newFixedThreadPool(8);
		
		for(int i=0; i < this.numberOfPeople; i++)
		{
			final int index = i;
			threadPool.submit( () -> generatePerson(index) );
		}

		try 
		{
			threadPool.shutdown();
			while (!threadPool.awaitTermination(30, TimeUnit.SECONDS))
			{
				System.out.println("Waiting for threads to finish... " + threadPool);
			}
		} catch (InterruptedException e)
		{
			e.printStackTrace();
		}
		
		// have to store providers at the end to correctly capture utilization #s
		// TODO - de-dup hospitals if using a file-based database?
		if (database != null)
		{
			database.store( Hospital.getHospitalList() );
			
			List<CommunityHealthWorker> chws = CommunityHealthWorker.workers
					.values().stream().flatMap(List::stream)
					.collect(Collectors.toList());
			database.store(chws);
		}
		String url=Exporter.runPostCompletionExports(this);
		return url;
	}
	
	
	public Person generatePerson(int index)
	{
		Person person = null;
		try 
		{
			boolean isAlive = true;
			//TODO HardCoding CityName
			String cityName = "Dedham";
			Demographics city = demographics.get(cityName);
			if (city == null && cityName.endsWith(" Town"))
			{
				cityName = cityName.substring(0, cityName.length() - 5);
				city = demographics.get(cityName);
			}
			
			do
			{
				List<Module> modules = Module.getModules();
				
				// System.currentTimeMillis is not unique enough
				long personSeed = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
				person = new Person(personSeed);
				
				// TODO - this is quick & easy to implement,
				// but we need to adapt the ruby method of pre-defining all the demographic buckets
				// and then putting people into those
				// -- but: how will that work with seeds?
				long start = setDemographics(person, cityName, city);
			
				LifecycleModule.birth(person, start);
				EncounterModule encounterModule = new EncounterModule();
					
				long time = start;
				while(person.alive(time) && time < stop)
				{
					encounterModule.process(person, time);
					Iterator<Module> iter = modules.iterator();
					while(iter.hasNext())
					{
						Module module = iter.next();
		//				System.out.format("Processing module %s\n", module.name);
						if(module.process(person, time))
						{
		//					System.out.format("Removing module %s\n", module.name);
							iter.remove(); // this module has completed/terminated.
						}
					}
					encounterModule.endWellnessEncounter(person, time);

					// TODO: if CHW policy is enabled for community, possibly add CHW interventions
					// if true
					// then add chw encounter to record
					// and set chw variable(s) on person.attributes.put(KEY, VALUE)
			
					
					time += timestep;
				}
				
				DeathModule.process(person, time);
				
				Exporter.export(person, stop);
				if (database != null)
				{
					database.store(person);
				}
				
				isAlive = person.alive(time);
				
				if (!this.logLevel.equals("none"))
				{
					writeToConsole(person, index, time, isAlive);
				}
				
				String key = isAlive ? "alive" : "dead";
				
				AtomicInteger count = stats.get(key);
				count.incrementAndGet();
			} while (!isAlive);
		} catch (Throwable e) // lots of fhir things throw errors for some reason
		{
			e.printStackTrace();
			throw e;
		}
		return person;
	}
	
	private synchronized void writeToConsole(Person person, int index, long time, boolean isAlive)
	{
		// this is synchronized to ensure all lines for a single person are always printed consecutively
		String deceased = isAlive ? "" : "DECEASED";
		System.out.format("%d -- %s (%d y/o) %s %s\n", index+1, person.attributes.get(Person.NAME), person.ageInYears(time), person.attributes.get(Person.CITY), deceased);
		
		if (this.logLevel.equals("detailed"))
		{
			System.out.println("ATTRIBUTES");
			for(String attribute : person.attributes.keySet()) {
				System.out.format("  * %s = %s\n", attribute, person.attributes.get(attribute));
			}
			System.out.format("SYMPTOMS: %d\n", person.symptomTotal());
			System.out.println(person.record.textSummary());
			System.out.println("VITAL SIGNS");
			for(VitalSign vitalSign : person.vitalSigns.keySet()) {
				System.out.format("  * %25s = %6.2f\n", vitalSign, person.getVitalSign(vitalSign).doubleValue());
			}
			System.out.format("Number of CHW Interventions: %d\n", person.attributes.get(Person.CHW_INTERVENTION));
			System.out.println("-----");
		}
	}
	
	private long setDemographics(Person person, String cityName, Demographics city)
	{
		person.attributes.put(Person.CITY, cityName);

		String race = city.pickRace(person.random);
		person.attributes.put(Person.RACE, race);
		
		String gender = city.pickGender(person.random);
		if (gender.equalsIgnoreCase("male") || gender.equalsIgnoreCase("M"))
		{
			gender = "M";
		} else
		{
			gender = "F";
		}
		person.attributes.put(Person.GENDER, gender);

		// Socioeconomic variables of education, income, and education are set.
		String education = city.pickEducation(person.random);
		person.attributes.put(Person.EDUCATION, education);
		double education_level = city.educationLevel(education, person);
		person.attributes.put(Person.EDUCATION_LEVEL, education_level);

		int income = city.pickIncome(person.random);
		person.attributes.put(Person.INCOME, income);
		double income_level = city.incomeLevel(income);
		person.attributes.put(Person.INCOME_LEVEL, income_level);

		double occupation = person.rand();
		person.attributes.put(Person.OCCUPATION_LEVEL, occupation);

		double ses_score = city.socioeconomicScore(income_level, education_level, occupation);
		person.attributes.put(Person.SOCIOECONOMIC_SCORE, ses_score);
		person.attributes.put(Person.SOCIOECONOMIC_CATEGORY, city.socioeconomicCategory(ses_score));
		
		long targetAge = city.pickAge(person.random);
		
		// TODO this is terrible date handling, figure out how to use the java time library
        long earliestBirthdate = stop - TimeUnit.DAYS.toMillis((targetAge + 1) * 365L + 1);
        long latestBirthdate = stop - TimeUnit.DAYS.toMillis(targetAge * 365L);

        long birthdate = (long) person.rand(earliestBirthdate, latestBirthdate);
		
		return birthdate;
	}
}
